## Raft

Raft 是一种共识算法(Concensus Algorithm)，在容错(fault-tolerance)和性能(performance)上和 Paxos 等价但是更容易理解。和 Paxos 不同的是，Raft 将共识问题分解为相对独立的子问题，并通过解决子问题从而达成共识。


Raft 将数据一致性的问题分解为 leader election、log replication、safety、membership changes 这些子问题，并且将一致性过程中节点的状态简化为 leader、follower 和 cadidate 三种状态，使得算法更加清晰。

Raft 协议通过选举 leader，然后使 leader 来负责管理整个 replicated log 从而实现一致性。leader 负责接收客户端的更新请求，然后复制到 follwer 节点，并且会通知 follwer 在安全的时候将日志作用与自己的状态机。

### Leader 选举

Raft 协议中每个节点在任意时刻会处于三种状态之一：leader、follower、candidate。正常情况下会有一个节点处于 leader 状态，而其他节点处于 follower 状态。

开始时所有的节点都处于 follower 状态，节点等待选举超时(election timeout) 时间后变为 candidate 角色，此时就会开始选举，Raft 为了避免多个节点同时触发选举使用了随机选举超时时间，也就是每个 follower 的等待时间是随机的。

处于 candidate 的节点发起 leader 选举时会先给自己投一票，然后向其他节点发送投票请求，节点收到投票请求后如果发现尚未进行此轮投票则进行投票，然后重置自己的选举超时时间，candidate 节点此时会有三种情况：

- candidate 节点收到多数节点的选票，此时节点成功选举为 leader，然后向所有节点发送AppendEntries 心跳消息
- candidate 节点在等待投票时收到其他节点发出的 AppendEntries 心跳消息，此时通过自己的 term 编号和心跳消息的 term 编号比较，如果自己编号较大则继续等待，否则承认 leader 已经选举成功，将自己转为 follower 状态
- candidate 节点等待超时仍未收到多数选票或者 leader 心跳信息，则说明此轮 leader 选举失败，需要重新发起一轮选举

### 日志复制

主节点选举成功之后，所有的读写操作都是由主节点完成。主节点在完成数据变更后需要将变更的数据复制到所有 follower 节点。

客户端提交的写命令会按顺序记录在 leader 的日志中，每条命令都包含 term 编号和顺序索引。leader 发送 AppendEntries 信息到所有的 follower，当大多数 follwer 节点返回成功时，leader 会同时做三件事：

- 将日志应用到本地的复制状态机
- 通知所有的 follower 进行日志提交，然后应用到本地的复制状态机

### 安全性