###  Raft (algorithm)

**Raft** is a [consensus](https://en.wikipedia.org/wiki/Consensus_(computer_science)) algorithm designed as an alternative to the [Paxos](https://en.wikipedia.org/wiki/Paxos_(computer_science)) family of algorithms. It was meant to be more understandable than Paxos by means of separation of logic, but it is also formally proven safe and offers some additional features.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1) Raft offers a generic way to distribute a [state machine](https://en.wikipedia.org/wiki/Finite-state_machine) across a [cluster](https://en.wikipedia.org/wiki/Computer_cluster) of computing systems, ensuring that each node in the cluster agrees upon the same series of state transitions. It has a number of open-source reference implementations, with full-specification implementations in [Go](https://en.wikipedia.org/wiki/Go_(programming_language)), [C++](https://en.wikipedia.org/wiki/C%2B%2B), [Java](https://en.wikipedia.org/wiki/Java_(programming_language)), and [Scala](https://en.wikipedia.org/wiki/Scala_(programming_language)).[[2\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-website-2) It is named after Reliable, Replicated, Redundant, And Fault-Tolerant.[[3\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-3)

Raft is not a [Byzantine fault](https://en.wikipedia.org/wiki/Byzantine_fault) tolerant algorithm: the nodes trust the elected leader.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1)



Raft achieves consensus via an elected leader. A server in a raft cluster is either a *leader* or a *follower*, and can be a *candidate* in the precise case of an election (leader unavailable). The leader is responsible for log replication to the followers. It regularly informs the followers of its existence by sending a heartbeat message. Each follower has a timeout (typically between 150 and 300 ms) in which it expects the heartbeat from the leader. The timeout is reset on receiving the heartbeat. If no heartbeat is received the follower changes its status to candidate and starts a leader election.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1)[[4\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-secretlives-4)



### Approach of the consensus problem in Raft[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=2)]

Raft implements consensus by a leader approach. The cluster has one and only one elected leader which is fully responsible for managing log replication on the other servers of the cluster. It means that the leader can decide on new entries' placement and establishment of data flow between it and the other servers without consulting other servers. A leader leads until it fails or disconnects, in which case a new leader is elected.

The consensus problem is decomposed in Raft into two relatively independent subproblems listed down below.

#### Leader election[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=3)]

When the existing leader fails or when the algorithm initializes, a new leader needs to be elected.

In this case, a new *term* starts in the cluster. A term is an arbitrary period of time on the server for which a new leader needs to be elected. Each term starts with a leader election. If the election is completed successfully (i.e. a single leader is elected) the term keeps going with normal operations orchestrated by the new leader. If the election is a failure, a new term starts, with a new election.

A leader election is started by a *candidate* server. A server becomes a candidate if it receives no communication by the leader over a period called the *election timeout*, so it assumes there is no acting leader anymore. It starts the election by increasing the term counter, voting for itself as new leader, and sending a message to all other servers requesting their vote. A server will vote only once per term, on a first-come-first-served basis. If a candidate receives a message from another server with a term number larger than the candidate's current term, then the candidate's election is defeated and the candidate changes into a follower and recognizes the leader as legitimate. If a candidate receives a majority of votes, then it becomes the new leader. If neither happens, e.g., because of a split vote, then a new term starts, and a new election begins.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1)

Raft uses a randomized election timeout to ensure that split vote problems are resolved quickly. This should reduce the chance of a split vote because servers won't become candidates at the same time: a single server will time out, win the election, then become leader and send heartbeat messages to other servers before any of the followers can become candidates.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1)

#### Log replication[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=4)]

The leader is responsible for the log replication. It accepts client requests. Each client request consists of a command to be executed by the replicated state machines in the cluster. After being appended to the leader's log as a new entry, each of the requests is forwarded to the followers as AppendEntries messages. In case of unavailability of the followers, the leader retries AppendEntries messages indefinitely, until the log entry is eventually stored by all of the followers.

Once the leader receives confirmation from the majority of its followers that the entry has been replicated, the leader applies the entry to its local state machine, and the request is considered *committed*.[[1\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-paper-1)[[4\]](https://en.wikipedia.org/wiki/Raft_(algorithm)#cite_note-secretlives-4) This event also commits all previous entries in the leader's log. Once a follower learns that a log entry is committed, it applies the entry to its local state machine. This ensures consistency of the logs between all the servers through the cluster, ensuring that the safety rule of Log Matching is respected.

In the case of leader crash, the logs can be left inconsistent, with some logs from the old leader not being fully replicated through the cluster. The new leader will then handle inconsistency by forcing the followers to duplicate its own log. To do so, for each of its followers, the leader will compare its log with the log from the follower, find the last entry where they agree, then delete all the entries coming after this critical entry in the follower log and replace it with its own log entries. This mechanism will restore log consistency in a cluster subject to failures.



### Safety[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=5)]

#### Safety rules in Raft[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=6)]

Raft guarantees each of these safety properties :

- **Election safety:** at most one leader can be elected in a given term.
- **Leader append-only:** a leader can only append new entries to its logs (it can neither overwrite nor delete entries).
- **Log matching:** if two logs contain an entry with the same index and term, then the logs are identical in all entries up through the given index.
- **Leader completeness:** if a log entry is committed in a given term then it will be present in the logs of the leaders since this term
- **State machine safety:** if a server has applied a particular log entry to its state machine, then no other server may apply a different command for the same log.

The first four rules are guaranteed by the details of the algorithm described in the previous section. The State Machine Safety is guaranteed by a restriction on the election process.

#### State machine safety[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=7)]

This rule is ensured by a simple restriction: a candidate can't win an election unless its log contains all committed entries. In order to be elected, a candidate has to contact a majority of the cluster, and given the rules for logs to be committed, it means that every committed entry is going to be present on at least one of the servers the candidates contact.

Raft determines which of two logs (carried by two distinct servers) is more up-to-date by comparing the index term of the last entries in the logs. If the logs have a last entry with different terms, then the log with the later term is more up-to-date. If the logs end with the same term, then whichever log is longer is more up-to-date.

In Raft, the request from a candidate to a voter includes information about the candidate's log. If its own log is more up-to-date than the candidate's log, the voter denies its vote to the candidate. This implementation ensures the State Machine Safety rule.

#### Follower crashes[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=8)]

If a follower crashes, *AppendEntries* and *vote* requests sent by other servers will fail. Such failures are handled by the servers trying indefinitely to reach the downed follower. If the follower restarts, the pending requests will complete. If the request has already been taken into account before the failure, the restarted follower will just ignore it.

#### Timing and availability[[edit](https://en.wikipedia.org/w/index.php?title=Raft_(algorithm)&action=edit&section=9)]

Timing is critical in Raft to elect and maintain a steady leader over time, in order to have a perfect availability of the cluster. Stability is ensured by respecting the *timing requirement* of the algorithm :

> *broadcastTime << electionTimeout << MTBF*

- *broadcastTime* is the average time it takes a server to send request to every server in the cluster and receive responses. It is relative to the infrastructure used.
- *MTBF (Mean Time Between Failures)* is the average time between failures for a server. It is also relative to the infrastructure.
- *electionTimeout* is the same as described in the Leader Election section. It is something the programmer must choose.

Typical number for these values can be 0.5 ms to 20 ms for *broadcastTime*, which implies that the programmer sets the *electionTimeout* somewhere between 10 ms and 500 ms. It can take several weeks or months between single server failures, which means the values are sufficient for a stable cluster.