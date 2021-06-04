## KafkaController

`KafkaController` 管理整个集群中的所有分区以及副本的状态，负责分区 leader 副本的选举、分区在 broker 上的分配以及同步元数据信息到 broker。

Kafka 集群会选举一个 broker 作为 Controller，每个 Broker 在启动时会监听 `ZooKeeper` 的 `/controller` 结点，如果不存在则说明尚未选举出 Controller，此时 broker 会尝试创建 `/controller` 结点，如果创建成功则该 broker 成为 Controller。


ZooKeeper 中还有一个与控制器相关的 `/controller_epoch` 持久节点，节点中存放的是一个整型的 controller_epoch 值用于记录控制器发生变更的次数。controller_epoch 初始值为 1，当控制器发生变更时每新选出一个控制器就加 1。每个和控制器交互的请求都会携带 controller_epoch 字段，如果请求值小于控制器的 controller_epoch 则说明请求是向过期的控制器发送的那么这个请求会被认定为无效，如果请求值大于则说明已经有新的控制器当选。

控制器在选举成功后会读取 ZooKeeper 中各个节点的数据来初始化上下文(ControllerContext)信息，并且需要管理这些上下文信息。不管是监听器触发的事件或是其他事件都会读取或更新控制器中的上下文信息，Kafka 控制器使用单线程基于事件队列的模型，将每个事件做一层封装后按照事件发生的先后顺序暂存到 LinkedBlockingQueue 中，然后使用一个专门的线程(ControllerEventThread)按照 FIFO 顺序处理各个事件。

每个 broker 节点都会监听 /controller 节点，当数据发生变化时每个 broker 都会更新自身保存的 activeControllerId。如果 broker 控制器发生变更需要关闭相应的资源如关闭状态机、注销相应的监听器等。

### 分区 Leader 选举

KafkaController 负责选举主题分区的 leader 副本，Kafka 提供了多种分区 leader 副本的选举策略，应用在不同的场景。

Kafka 分区 leader 副本选举策略由 `PartitionLeaderSelector` 定义，其将当前分区的 leader 副本以及 ISR 副本集合作为参数，返回新选举的 leader 副本以及新的 ISR 副本集合。

```scala
trait PartitionLeaderSelector {

  // topicAndPartition 表示需要选举 leader 的主题分区
  // currentLeaderAndIsr 表示从 ZK 上读取的当前主题分区的 leader 和 isr
  def selectLeader(topicAndPartition: TopicAndPartition, currentLeaderAndIsr: LeaderAndIsr): (LeaderAndIsr, Seq[Int])

}
```

- `NoOpLeaderSelector `：不进行分区 leader 的选举，返回主题分区当前的 leader 副本以及 ISR 集合

- `OfflinePartitonLeaderSelector`：创建新的 topic 或者 leader 副本不存在时触发的选举策略。如果 isr 中有存活的副本则选择第一个存活的副本作为 leader，isr 中存活的副本集合则为新的 isr 集合；如果 isr 列表为空，且 `unclean.leader.election.enable` 设置为 false 则抛出 `NoReplicaOnlineException`；否则表示可以选举不在 isr 中的副本作为 leader，此时直接从存活 AR 集合中选择第一个副本作为 leader 副本；如果没有存活的副本则抛出 `NoReplicaOnlineException`
- `ReassignedPartitionLeaderSelector`：主题发生分区重分配时触发的选举策略，直接将重新分配后存活的 isr 副本集合中的第一个副本作为新的 leader 副本
- `PreferredReplicaPartitionLeaderSelector`：执行优先副本选举时触发的选举策略。该策略首先从已经分配的副本集合 (AR) 中取出第一个副本，如果选择的副本是当前分区的 leader 副本或者不在 ISR 集合中，则会抛出异常，否则将该副本作为分区的新的 leader 副本，新的 isr 副本集合和原有的 isr 副本集合相同
- `ControlledShutdownLeaderSelector`：分区 leader 副本所在的 broker shutdown 时触发的选举策略。先找出已经分配的副本集合(assigned Replicas, AR)，然后过滤出仍然存活的副本集合，并选取该集合中的第一个副本作为新的 leader 副本，新的 isr 副本集合则是过滤掉分区在该 broker 上的副本后的副本集合

### 元数据管理

`KafkaController` 和集群中的每个 Broker 建立连接，并且为每个 TCP 连接建立 `RequestSendThread` 用于发送请求，在集群元数据发生变化时向所有的 Broker 广播集群的元数据信息。

`KafkaController` 会向 Broker 发送三种请求：

- `LeaderAndIsrRequest`：通知 Broker 主题分区的 leader 副本以及 ISR 副本所在的 Broker 信息
- `StopReplicaRequest`：通知 Broker 停止指定副本的数据请求操作，主要用于分区副本迁移和删除主题的操作
- `UpdateMetadataRequest`：更新 Broker 上集群的元数据信息

`KafkaController` 将需要向 Broker 发送的请求加入 `BlockingQueue`，然后 `RequestSendThread` 线程从队列中取出需要发送的请求并发送，在等待响应的过程中该线程会一直阻塞，当接收到响应后则调用 `callback` 回调函数处理响应数据。 

##### broker.id
自动生成 brokerId 的原理是先往 ZooKeeper 中的 /brokers/seqid 节点中写入一个空字符串，然后获取返回的 Stat 信息中的 version 值，进而将 version 的值和 reserved.broker.max.id 参数配置的值相加。先往节点中写入数据再获取 Stat 信息可以确保返回的 version 值大于 0 进而可以确保生成的 brokerId 值大于 reserved.broker.max.id 参数配置的值。



http://ifeve.com/kafka-controller/



https://segmentfault.com/a/1190000021952571