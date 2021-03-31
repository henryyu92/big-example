## ZAB 协议
ZAB(Zookeeper Atomic Broadcast) 协议是 Zookeeper 用于支持崩溃恢复和原子广播的协议，通过分布式数据一致性算法保证分布式事务的最终一致性。

Zookeeper 基于 zab 协议实现了一种主备模型(即 leader 和 follower 模型)，leader 负责处理事务请求，然后将数据同步给 follower 节点。Zookeeper 客户端随机连接到集群中的一个节点，如果是读请求则直接从当前节点读取数据，如果是写请求则会向 leader 节点提交事务，leader 接收到事务请求之后会广播事务，只要超过半数节点写入成功，该事务就会被提交。

为了保证数据的正确性且不丢失数据，ZAB 协议需要确保：
- 已经在 leader 提交(commit)的事务最终被所有的节点写入，即不丢失数据
- 丢弃那些只在 leader 上提出却没有被提交的事务，即数据的正确性
- leader 发起的事务请求必须保持顺序性，即只有前面的事务被处理完之后才能处理下一个事务

Zab 协议定义了事务请求的处理方式：
- 所有的事务请求必须由一个全局唯一的服务器来协调处理，即 leader 节点，其他的都是 follower 节点
- leader 节点负责将一个客户端事务请求转换成事务提案(proposal)，并将该 proposal 分发给集群中所有的 follower，也就是向所有 follower 节点发送数据广播请求
- 分发之后 leader 需要等待所有 follower 的 ACK 响应，在 ZAB 协议中只要超过半数的 follower 正确响应后就会再次向所有的 follower 发送 commit 消息将事务 proposal 提交

ZAB 协议包括**崩溃恢复**和**消息广播**两种模式。当整个集群启动或者 leader 节点异常时进入崩溃恢复模式选举产生新的 leader，当选举产生新的 leader 同时集群中超过半数的节点完成与 leader 节点的状态同步后，集群退出崩溃恢复模式进入消息广播模式。

在整个消息广播中，leader 将事务请求转换为 proposal 进行广播，并且在广播 proposal 之前为其分配一个全局递增的唯一 id，称为事务 ID(即 zxid)，ZAB 协议需要保证消息的严格顺序，因此必须将每个 proposal 按照其 zxid 先后顺序进行处理。

### 消息广播

Zab 协议中的数据同步方式与二阶段提交相似，二阶段提交需要协调者必须等到所有的参与者全部返回 ack 消息后才会发送 commit 消息，Zab 协议中 leader 只需要等到超过半数的 follower 返回 ack 消息就可以发送 commit 消息。
- 客户端发起事务请求
- leader 将客户端请求转化为事务 proposal，同时为每个 proposal 分配一个全局递增唯一 ID，即 zxid
- leader 为每个 follower 维护一个单独的 FIFO 队列，然后将 proposal 放入到队列中，发送给每个 follower
- follower 接收到 proposal 之后会以事务日志的形式写入到磁盘中，然后向 leader 返回 ACK 响应
- 如果 leader 接收到超过半数以上的 follower 的 ACK 消息后就任务事务提交成功，leader 在本地完成事务提交之后向 follower 发送 commit 消息通知 follower 提交事务

```
问题：
    1. leader 发送事务请求之后异常，事务是否会提交？
    2. leader 在 commit 之前异常，事务是否提交？
    3. follower 在 commit 之前异常，如何保证数据一致？
```

### 崩溃恢复

一旦 leader 异常或者由于网络分区导致 leader 与过半 leader 失去联系就会进入崩溃恢复模式，崩溃恢复模式包括两部分：leader 选举 和 数据恢复。

#### Leader 选举
根据 ZAB 的数据一致性要求，新选出的 leader 中必须含有最大的 zxid，zxid 有 64 位，其中高 32 位是 epoch 编号，每一轮 leade选举需要增加 1，低 32 位是事务计数器，每个事务请求递增 1。

leader 选举会在集群初次启动和 leader 节点异常时发生。

集群启动时每个节点状态都是 looking，接下来就开始了选主流程：
- 每个节点广播投票，广播内容位 (zxid, myid)，集群启动时每个节点广播的是自己的 zxid 和 myid
- 每个节点接收其他节点的投票并检查投票是否来自本轮(epoch)、是否是 looking 节点的投票等有效性检查
- 每个节点处理有效的投票，节点比较投票的值以确定选举的 leader
  - 首先比较 zxid，zxid 较大的作为承认的 leader。zxid 较大能够保证已经 commit 的提案最终会被提交
  - 如果 zxid 相同则比较 myid，较大的作为承认的 leader
- 每个节点统计此轮的投票，如果超过半数的节点承认相同的 leader 则认为 leader 已经确定
- 一旦 leader 确定后，各个节点将各自的状态更改位 leading 或者 following

集群 leader 异常后再恢复数据之前需要先重新选举 leader：
- follower 将自身的状态变为 looking
- looking 状态的节点开始选举 leader 节点，流程和启动时相同

#### 数据同步

leader 选举完成后再正式接收事务请求之前需要确认事务日志中所有的 proposal 是否已经被集群中过半的服务器接受。
- follower 向 leader 发送 epoch
- leader 从发送的 epoch 中选取最大的 +1 形成本轮的 epoch 发送给所有 follower
- follower 收到新的 epoch 后将自己的 zxid 发送给 leader
- leader 根据接收到的 zxid 集合确定同步数据同步给 follower，即未同步数据执行 commit，不需要同步的数据执行终止操作

数据同步完成之后 leader 才能对外接收事务请求。




https://www.jianshu.com/p/2bceacd60b8a