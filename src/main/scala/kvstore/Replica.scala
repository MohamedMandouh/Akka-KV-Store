package kvstore

import akka.Done
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorRef, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import akka.japi.Pair
import kvstore.Arbiter._
import akka.pattern.{ask, pipe}

import scala.concurrent.duration._
import akka.util.Timeout
import kvstore.Timer.TimePassed

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

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher
  import Timer._

  override val supervisorStrategy = OneForOneStrategy() { case _: PersistenceException => Restart}

  context.system.scheduler.scheduleWithFixedDelay(100.millisecond,100.millisecond){ new Runnable {
    override def run(): Unit = if(!persisted) persistence ! pending.second
  }}

  // a map from secondary replicas to replicators
  var kv = Map.empty[String, String]
  // the current set of replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  //Persistence Service
  val persistence = context.actorOf(persistenceProps,"MyChild")
  //Stores Current Update
  var pending : Pair[ActorRef,Persist] = Pair(self,Persist("none",None,-1))
  //set of acknowledgments from Replicators
  var acks  = Set.empty[ActorRef]
  // an actor used to Set a timeout of 1 second for each update
  var timer : ActorRef= _
  var persisted = true
  //ignore all timeouts fired from timer after we confirmed the update
  var ignoreTimer = false
  var updateGoing= false

  //First: primary joins the system
  arbiter ! Join

  def receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }
  val leader: Receive = {
    case Replicated(key,id) if id != -1  =>
      acks -= sender
      if(acks.isEmpty&& persisted) {
        context.stop(timer)
        ignoreTimer =true
        updateGoing=false
        pending.first ! OperationAck(id)
      }

    case Persisted(key,id) =>
      persisted = true
      if(acks.isEmpty) {
        context.stop(timer)
        ignoreTimer = true
        updateGoing=false
        pending.first ! OperationAck(id)
      }

    case Timer.TimePassed(id,ref) if !ignoreTimer =>  ref ! OperationFailed(id)

    case Get(key,id) => sender ! GetResult(key,kv get key,id)

    case Insert(key,value,id) =>
      timer = context.actorOf(Props(new Timer(id,sender,self)))
       kv += (key -> value)
      persistence ! Persist(key,Some(value),id)
      persisted=false
      ignoreTimer=false
      updateGoing=true
      pending = Pair(sender,Persist(key,Some(value),id))
      acks = secondaries.values.toSet
      acks foreach(_ ! Replicate(key,Some(value),id))

    case Remove(key,id) =>
     timer = context.actorOf(Props(new Timer(id,sender,self)))
      kv -= key
      persistence ! Persist(key,None,id)
      persisted=false
      ignoreTimer=false
      updateGoing=true
      pending = Pair(sender,Persist(key,None,id))
      acks = secondaries.values.toSet
      acks foreach(_ ! Replicate(key,None,id))

    case  Replicas(all)  =>
      val replicas = all - self
      val oldReplicas = secondaries.keys.toSet -- replicas
      val oldReplicators = oldReplicas map secondaries
      acks --= oldReplicators
      if(acks.isEmpty && updateGoing) {
          pending.first ! OperationAck(pending.second.id)
      }
      oldReplicators foreach (_ ! PoisonPill)
      val newReplicaReplicator = (for{
        r <- replicas -- secondaries.keys.toSet
      } yield (r-> context.actorOf(Props(new Replicator(r))))).toMap

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
        persisted=false
        pending = Pair(sender,Persist(key,valueOption,seq))
      }
      else if(seq<expected) sender ! SnapshotAck(key,seq)
    case Persisted(key ,seq) =>
      persisted=true
      pending.first ! SnapshotAck(key,seq)
}
}

object Timer {
  case class TimePassed(id:Long,ref : ActorRef)
}

class Timer(id :Long, ref : ActorRef, parent:ActorRef) extends  Actor{
  implicit val executionContext = context.system.dispatcher
  context.system.scheduler.scheduleOnce(1.second){
    parent ! TimePassed(id,ref)
  }
  override def receive: Receive = {case _=> context.stop(self)}
}


