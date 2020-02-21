# Project for "Programming Reactive Systems" course on edX.

## Task: 
- [x] Implement the primary replica role so that it correctly responds to the KV protocol messages without considering persistence or replication.
- [x] Implement the secondary replica role so that it correctly responds to the read-only part of the KV protocol and accepts the replication protocol, without considering persistence.
- [x] Implement the replicator so that it correctly mediates between replication requests, snapshots and acknowledgements.
- [x] Implement the use of persistence at the secondary replicas.
- [x] Implement the use of persistence and replication at the primary replica.
- [x] Implement the sending of the initial state replication to newly joined replicas.

## Overview of the system components
The key-value store and its environment consists of the following components:

- **The clustered key-value store:** A set of nodes that store key value pairs in a distributed fashion, cooperating to maintain a certain set of guarantees (specified in section “System Behavior - Consistency guarantees”). This cluster of nodes consists of replicas and the provided Arbiter and Persistence modules:
- **Primary replica** A distinguished node in the cluster that accepts updates to keys and propagates the changes to secondary replicas.
- **Secondary replicas Nodes** that are in contact with the primary replica, accepting updates from it and serving clients for read-only operations.
- **Arbiter:** A subsystem that is provided in this exercise and which assigns the primary or secondary roles to your nodes.
- **Persistence:** A subsystem that is provided in this exercise and which offers to persist updates to stable storage, but might fail spuriously.
- **Clients:** Entities that communicate with one of the replicas to update or read key-value pairs.

## Replication Protocol:
When a new replica joins the system, the primary receives a new Replicas message and must allocate a new actor of type Replicator for the new replica.
When a replica leaves the system its corresponding Replicator must be terminated. The role of this Replicator actor is to accept update events, and propagate the changes to its corresponding replica (i.e. there is exactly one Replicator per secondary replica). Also, at creation time of the Replicator, the primary must forward update events for every key-value pair it currently holds to this Replicator.

```class Replicator(val replica: ActorRef)``` includes two pairs of messages:

The first one is used by the replica actor which requests replication of an update:
- ```Replicate(key, valueOption, id)``` is sent to the Replicator to initiate the replication of the given update to the key.
- ``` Replicated(key, id)``` is sent as a reply to the corresponding Replicate message once replication of that update has been successfully completed.

The second pair is used by the replicator when communicating with its partner replica:
- ```Snapshot(key, valueOption, seq)``` is sent by the Replicator to the secondary replica to indicate a new state of the given key.
- ```SnapshotAck(key, seq)```is the reply sent by the secondary replica to the Replicator as soon as the update is persisted locally by the secondary replica.

The Snapshot message provides a sequence number (seq) to enforce ordering between the updates. Updates for a given secondary replica must be processed in contiguous ascending sequence number order; this ensures that updates for every single key are applied in the correct order. Each Replicator uses its own number sequence starting at zero.

## Clients and The KV Protocol:

### Update Commands
- ```Insert(key, value, id)```  instructs the primary to insert the (key, value) pair into the storage and replicate it to the secondaries.
- ``` Remove(key, id)``` instructs the primary to remove the key (and its corresponding value) from the storage and then remove it from the secondaries.
A successful Insert or Remove results in a reply to the client in the form of an OperationAck(id) message where the id field matches the corresponding id field of the operation that has been acknowledged.
 
A failed Insert or Remove command results in an OperationFailed(id) reply. A failure is defined as the inability to confirm the operation within 1 second.

### Lookup
- ```Get(key, id)``` instructs the replica to look up the current value assigned with the key in the storage and reply with the stored value.
- A Get operation results in a ```GetResult(key, valueOption, id)``` message to be sent back to the sender of the lookup request.The valueOption field should contain None if the key is not present in the replica or Some(value) if a value is currently assigned to the given key in that replica.

## Arbiter:
The Arbiter is an external subsystem that is provided in the assignment. 
New replicas must first send a Join message to the Arbiter signaling that they are ready to be used.
The Join message will be answered by either a JoinedPrimary or JoinedSecondary message indicating the role of the new node
The arbiter will send a Replicas message to the primary replica that contains the set of available replica nodes including the primary and all the secondaries whenever it receives the Join message.

## Persistence:
Each replica will have to submit incoming updates to the local Persistence actor and wait for its acknowledgement before confirming the update to the requester.
- ```Persist(key, valueOption, id)``` is sent to the Persistence actor to request the given state to be persisted.
- ```Persisted(key, id)``` is sent by the Persistence actor as reply in case the corresponding request was successful; no reply is sent otherwise.

The provided implementation of this persistence service is a mock in the true sense, since it is rather unreliable: every now and then it will fail with an exception and not acknowledge the current request. It is the job of the Replica actor to create and appropriately supervise the Persistence actor
