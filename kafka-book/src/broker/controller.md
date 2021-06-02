## KafkaController

`KafkaController` 管理整个集群中的所有分区以及副本的状态，负责分区 leader 副本的选举、分区在 broker 上的分配以及同步元数据信息到 broker。

Kafka 集群会选举一个 broker 作为 Controller，每个 Broker 在启动时会监听 `ZooKeeper` 的 `/controller` 结点，如果不存在则说明尚未选举出 Controller，此时 broker 会尝试创建 `/controller` 结点，如果创建成功则该 broker 成为 Controller。


ZooKeeper 中还有一个与控制器相关的 `/controller_epoch` 持久节点，节点中存放的是一个整型的 controller_epoch 值用于记录控制器发生变更的次数。controller_epoch 初始值为 1，当控制器发生变更时每新选出一个控制器就加 1。每个和控制器交互的请求都会携带 controller_epoch 字段，如果请求值小于控制器的 controller_epoch 则说明请求是向过期的控制器发送的那么这个请求会被认定为无效，如果请求值大于则说明已经有新的控制器当选。

控制器在选举成功后会读取 ZooKeeper 中各个节点的数据来初始化上下文(ControllerContext)信息，并且需要管理这些上下文信息。不管是监听器触发的事件或是其他事件都会读取或更新控制器中的上下文信息，Kafka 控制器使用单线程基于事件队列的模型，将每个事件做一层封装后按照事件发生的先后顺序暂存到 LinkedBlockingQueue 中，然后使用一个专门的线程(ControllerEventThread)按照 FIFO 顺序处理各个事件。

每个 broker 节点都会监听 /controller 节点，当数据发生变化时每个 broker 都会更新自身保存的 activeControllerId。如果 broker 控制器发生变更需要关闭相应的资源如关闭状态机、注销相应的监听器等。


### Leader 选举

KafkaController 负责选举主题分区的 leader 副本，Kafka 提供了多种分区 leader 副本的选举策略，应用在不同的场景。

- `OfflinePartitonLeaderSelector`
- `ReassignedPartitionLeaderSelector`：从可用的 ISR 中选取第一个副本作为 leader，并更新 ISR 集合
- `PreferredReplicaPartitionLeaderSelector`：从 assignedReplicas 取出的第一个副本作为 leader
- `ControlledShutdownLeaderSelector`：将 ISR 中处于关闭状态的副本从集合中去掉并更新 ISR 集合，然后将 ISR 集合中的第一个副本作为 leader


分区 leader 副本的选举由控制器负责具体实施。当创建分区或分区上线的时候都需要执行 leader 的选举，对应的选举策略为 OfflinePartitionLeaderElectionStrategy。这种策略的基本思想是按照 AR 集合中副本的顺序查找第一个存活的副本，并且这个副本在 ISR 集合中。一个分区的 AR 集合在分配的时候就被指定，并且只要不发生重分配的情况，集合内部副本的顺序是保持不变的，而分区的 ISR 集合中副本的顺序可能会改变。

如果 ISR 中美欧可用的副本，那么如果 ```unclean.leader.election.enable``` 参数设置为 true(默认是 false)则表示允许从非 ISR 列表中选举 leader，即从 AR 集合中找到第一个存活的副本即为 leader。

当分区重分配时也需要执行 leader 的选举动作，对应的策略为 ReassignPartitionLeaderElectionStrategy。

当发生优先副本选举时，直接将优先副本设置为 leader 即可，AR 集合中的第一个副本即为优先副本(PreferredReplicaPartitionLeaderElectionStategy)

当某个节点被优雅关闭时，位于这个节点的 leader 副本会下线，对应的分区需要执行 leader 的选举，对应的选举策略为 ControlledShutdownPartitonLeaderElectionStrategy：从 AR 列表中找到第一个存活的副本，且这个副本在目前的 ISR 列表中，与此同时还要确保这个副本不处于正在被关闭的节点上。

分区状态：

- `NonExistentPartition`：分区没有被创建或者创建后被删除了
- `NewPartition`：分区创建之后的初始状态，分区存在副本，但是还没有 leader 和 ISR
- `OnlinePartition`：分区  Leader 副本选举成功时处于的状态
- `OfflinePartition`：分区 Leader 副本所在的 Broker 异常时的状态


### 元数据管理



##### broker.id
broker.id 是 broker 在启动之前必须设定的参数之一，在 Kafka 集群中，每个 broker 都有唯一的 id 值用于区分彼此。broker 在启动时会在 ZooKeeper 中的 /brokers/ids 路径下创建一个以当前 brokerId 为名称的虚节点，broker 的健康状态检查就依赖于此虚拟节点。当 broker 下线时，该虚节点会自动删除，其他 broker 节点或客户端通过判断 /brokers/ids 路径下是否有此 broker 的 brokerId 节点来确定该 broker 的健康状态。

可以通过 broker 端的配置文件 config/server.properties 里的 broker.id 参数来配置 brokerId，默认情况下 broker.id 的值为 -1。在 Kafka 中 brokerId 的值必须大于等于 0 才有可能正常启动，还可以通过 meta.properties 文件自动生成 brokerId。 meta.properties 文件在 broker 成功启动之后在每个日志根目录都会生成，与 broker.id 的关联如下：
- 如果 log.dir 或者 log.dirs 中配置了多个日志根目录，这些日志根目录中的 meta.properties 文件配置的 broker.id 不一致则会抛出 InconsistentBrokerIdException 的异常
- 如果 config/server.properties 配置文件里配置的 broker.id 的值和 meta.properteis 文件里的 broker.id 值不一致，那么同样会抛出 InconsistentBrokerIdException 的异常
- 如果 config/server.properties 配置文件中并未配置 broker.id 的值，那么就以 meta.properties 文件中的 broker.id 值为准
- 如果没有 meta.properties 文件，那么在获取合适的 broker.id 值之后会创建一个新的 meta.properties 文件并将 broker.id 值存入其中

如果 config/server.properteis 配置文件中没有配置 broker.id 并且日志根目录中也没有任何 meta.properties 文件，Kafka 提供了 broker 端参数 ```broker.id.generation.enable``` 和 ```reserved.broker.max.id``` 生成新的 brokerId。

```broker.id.generation.enable``` 参数用来配置是否开启自动生成 brokerId 的功能，默认情况是 true。自动生成的 brokerId 有一个基准值，自动生成的 brokerId 必须超过这个基准值，这个基准值由参数 ```reserved.broker.max.id``` 配置，默认是 1000，也就是说默认情况下自动生成的 brokerId 是从 1001 开始的。

自动生成 brokerId 的原理是先往 ZooKeeper 中的 /brokers/seqid 节点中写入一个空字符串，然后获取返回的 Stat 信息中的 version 值，进而将 version 的值和 reserved.broker.max.id 参数配置的值相加。先往节点中写入数据再获取 Stat 信息可以确保返回的 version 值大于 0 进而可以确保生成的 brokerId 值大于 reserved.broker.max.id 参数配置的值。

##### bootstrap.servers
```bootstrap.servers``` 参数指定 broker 的地址列表，用来发现 Kafka 集群元数据信息的服务地址。客户端连接 Kafka 集群需要经历 3 个过程：
- 客户端与 ```bootstrap.servers``` 参数所指定的 Server 连接，并发送 MetadataRequest 请求来获取集群的元数据信息
- Server 在收到 MetadataRequest 请求之后，返回 MetadataResponse 给客户端，MetadataResponse 中包含了集群的元数据信息
- 客户端在收到 MetadataResponse 之后解析出其中包含的源数据信息，然后与集群中的每个节点建立连接，之后就可以发送消息了

可以将 Server 角色和 Kafka 集群角色分开，实现自定义的路由、负载均衡等功能。



http://ifeve.com/kafka-controller/