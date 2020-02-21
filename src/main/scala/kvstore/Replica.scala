package kvstore

import akka.Done
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorRef, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import akka.japi.Pair
import kvstore.Arbiter._
import akka.pattern.{ask, pipe}

import scala.concurrent.duration._
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  case class TimePassed(key:String,id:Long)

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  // a map from secondary replicas to replicators
  var kv = Map.empty[String, String]
  // the current set of replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  //Persistence Service
  val persistence = context.actorOf(persistenceProps,"MyChild")
  //Stores Current Updates
  var pending  = Map.empty[String,Pair[ActorRef,Persist]]
  //Pair(self,Persist("none",None,-1))
  //set of acknowledgments from Replicators
  var acks  = Map.empty[String,Set[ActorRef]]
  //the keys from which we await confirmation
  var persisted = Map.empty[String,Boolean]
  var updateGoing = Map.empty[String,Boolean]

  //restart persistence service if failed
  override val supervisorStrategy = OneForOneStrategy() { case _: PersistenceException => Restart}

  // if not recieved confirmation from persistence for 100 ms; retry
  context.system.scheduler.scheduleWithFixedDelay(100.millisecond,100.millisecond){ new Runnable {
    override def run(): Unit = pending foreach(p => if(!persisted(p._1)) persistence ! p._2.second)
  }}
  //First: primary joins the system
  arbiter ! Join

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }
  val leader: Receive = {
    case Replicated(key,id) if id != -1  =>
      acks += (key-> acks(key).excl(sender))
      if(acks(key).isEmpty&& persisted(key)) {
        updateGoing+= key->false
        pending(key).first ! OperationAck(id)
        pending-= key
      }

    case Persisted(key,id) =>
      persisted += (key->true)
      if(acks(key).isEmpty) {
        updateGoing += key->false
        pending(key).first ! OperationAck(id)
        pending -= key
      }

    case TimePassed(key,id) => if (updateGoing(key)) {
      pending(key).first ! OperationFailed(id)
    }

    case Get(key,id) => sender ! GetResult(key,kv get key,id)

    case Insert(key,value,id) =>
      kv += (key -> value)
      persisted += key-> false
      updateGoing += key-> true
      persistence ! Persist(key,Some(value),id)
      pending += key-> Pair(sender,Persist(key,Some(value),id))
      acks += key -> secondaries.values.toSet
      acks(key) foreach(_ ! Replicate(key,Some(value),id))
      //allow 1 second for global acknowledgement of update
      context.system.scheduler.scheduleOnce(1.second){
        self ! TimePassed(key,id)
      }

    case Remove(key,id) =>
      kv -= key
      persisted += key-> false
      updateGoing += key-> true
      persistence ! Persist(key,None,id)
      pending += key-> Pair(sender,Persist(key,None,id))
      acks += key -> secondaries.values.toSet
      acks(key) foreach(_ ! Replicate(key,None,id))

      context.system.scheduler.scheduleOnce(1.second){
        self ! TimePassed(key,id)
      }

    case  Replicas(all)  =>
      val replicas = all - self
      val oldReplicas = secondaries.keys.toSet -- replicas
      val oldReplicators = oldReplicas map secondaries
      acks = for ((k,s) <- acks) yield  k -> s.removedAll(oldReplicators)
      oldReplicators foreach (_ ! PoisonPill)

      //trigger operation ack after old replicators have been removed
      acks.keys foreach(k => if(acks(k).isEmpty && updateGoing(k)) {
        pending(k).first ! OperationAck(pending(k).second.id)
      })

      val newReplicaReplicator = (for{
        r <- replicas -- secondaries.keys.toSet
      } yield r-> context.actorOf(Props(new Replicator(r)))).toMap

      secondaries --= oldReplicas
      secondaries ++= newReplicaReplicator

      newReplicaReplicator.values.foreach(x => kv.foreach(pair=> x ! Replicate(pair._1,Some(pair._2),-1)))
  }

  var expected = 0L
  val replica: Receive = {
    case Get(key,id) => sender ! GetResult(key ,kv get key,id)
    case Snapshot(key,valueOption,seq) =>
      if(expected == seq) {
        valueOption match {
          case Some(v) => kv += (key -> v)
          case None => kv -=  key
        }
        expected = seq +1
        persistence ! Persist(key,valueOption,seq)
        persisted+= key->false
        pending += key-> Pair(sender,Persist(key,valueOption,seq))
      }
      else if(seq<expected) sender ! SnapshotAck(key,seq)
    case Persisted(key ,seq) =>
      persisted+= key -> true
      pending(key).first ! SnapshotAck(key,seq)
  }
}


