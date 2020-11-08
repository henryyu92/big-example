### Kafka 架构

Kafka 是一个高性能、高吞吐的分布式事件流平台。事件流是指以流的方式从事件源实时捕获的数据，事件流被持久的存储起来用于后续的处理。Kafka 集成了三个关键功能使得事件流能够端到端的处理：

- 发布(`Publish`)/订阅(`Subscribe`)功能使得能够从一个系统传送/接收另一个系统的数据
- Kafka 提供了数据的持久化，可以存储事件流
- 可以在事件发生或者回溯时处理事件

Kafka 是由以 TCP 网络互相通信的 Server 和 Client 建立起来的分布式系统，其运行时架构如下：

![archi]()

- **生产者**
- **消费者**
- **Broker**
- **ZooKeeper**

### Kafka 术语

Kafka 可以很好的替代传统的消息队列，但是和传统的消息队列不同的是 Kafka 具有更高的吞吐量，内置的分区、复制和容错能力使得其能够满足大规模的消息处理场景。

**主题(Topic)**

主题是 Kafka 中的一个基本概念，Kafka 中的每个消息都属于某个特定的主题，只有订阅了该主题的消费者才能消费到对应的消息。

生产者发送的消息需要指定归属的主题，消息在 broker 中也是按照主题归类进行存储的。

**分区(Partition)**

主题是 Kafka 中的逻辑概念，每个主题可以划分为多个分区，每个分区只属于单个分区，因此分区也称为主题分区(`TopcPartition`)。归属同一个主题的消息存储在不同的分区，消费者订阅主题时会到指定的分区消费消息。

**偏移量(Offset)**

消息在 Kafka 中是以追加的形式存储的，每个分区的消息会分配一个严格递增的偏移量(`offset`)。偏移量是一个逻辑值，对消息偏移量的操作不会影响其本身的偏移量。

Kafka 没有为偏移量提供索引机制，但是通过偏移量可以实现消息在分区内的顺序性以及特定消息的消费。

**副本(Replica)**

Kafka 为了提升可靠性引入了多副本机制，每个分区都会有多个副本(Replica)，不同的副本保存在不同的 broker 中(broker 数量不少于副本数)。分区副本中 leader 副本负责消息的读取，follower 副本负责同步 leader 副本上的数据，当 leader 故障时从 follower 副本中选举出新的 leader 副本实现故障转移(`fali over`)。

分区副本一般会部署在不同的节点上，由于网络延迟会出现数据同步延迟的问题，为了保证数据的一致性，Kafka 将副本集合分为三类：

- **AR(Asigned Replicas)**：分区的所有副本
- **ISR(In-Sync Replicas)**：与 leader 副本同步延迟在范围内的副本集合
- **OSR(Out-of-Sync Replicas)**：与 leader 副本同步延迟超出范围的副本集合

leader 副本负责维护 ISR 集合，如果副本同步延迟超出范围则剔除出 ISR 集合，否则加入 ISR 集合。默认情况下，只有 ISR 集合中的副本能参与 leader 选举。

```
question: 如果实现 ISR 集合的维护？
```

Kafka 为了在数据一致性和高性能之间平衡规定消息只有写入了 ISR 集合才认为消费写入成功，因此 ISR 集合的大小以及副本间的同步速度决定了 Kafka 的性能。

**HW & LEO**

**HW(High Watermark)** 表示分区中第一个不可以消费的消息的偏移量(offset)，**LEO(Log End Offset)** 表示分区中下一个写入的消息的偏移量(offset)。

分区中的每个副本都会维护自己的 HW 和 LEO，因此 ISR 集合中最小的 LEO 即为分区的 HW。

### 安装部署

Kafka 集群由生产者、消费者、Broker 和 ZooKeeper 组成，其中生产者和消费者与业务绑定，因此不需要独立部署。ZooKeeper 集群通常作为集群管理、Master 选举、分布式协调组件被各个不同的业务公用，因此也不需要独立部署。

**Docker**

**K8S**

**参数配置**

Kafka 集群启动时会读取 `$KAFKA_HOME/config/server.properties` 文件中设置的参数，通过对这些参数的调优能够使得 Kafka 集群获得更好的性能。

- **`zookeeper.connect`**：指定 broker 需要连接的 ZooKeeper 集群的地址(包含端口号)，使用逗号 (,) 隔开集群的多个节点地址。在集群地址上增加一个 chroot 路径 (如 `localhost:2181/kafka`) 指定 Kafka 数据存储的根路径，可以使得 ZooKeeper 能够以多租户的方式为不同的业务提供服务
- **`listeners`**：指定 broker 监听客户端连接的地址列表，格式为 `protocol://hostname:port`，只有指定的客户端才能连接到 broker，默认值为 null 表示任意客户端可以连接。Kafka 支持三种协议：PLAINTEXT、SSL、SASL_SSL
- **`broker.id`**：指定当前 broker 的唯一标识，默认为 -1，即在集群启动时默认生成
- **`log.dir`**：指定 broker 存储数据的地址，默认为 `/tmp/kafak-logs`
- **`message.max.bytes`**：指定 broker 能接收的消息的最大值，默认为 1000012，超过指定大小的消息被发送到 broker 时会抛出 `RecordTooLargeException`

