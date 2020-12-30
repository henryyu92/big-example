## 消费者

消费者从 broker 上订阅主题，并从主题中拉取消息。除了消费者(Consumer)，Kafka 还有消费组(Consumer Group)，每个消费者都有一个对应的消费组，当消息发布到主题后只会被投递给订阅了该主题的消费组中的一个消费者。

消费组是一个逻辑上的概念，每个消费者只隶属于一个消费组，每个消费组有一个固定的名称，消费者在进行消费前需要指定其所属的消费组的名称，通过 ```group.id``` 来配置；消费者并非逻辑上的概念，它可以是一个进程也可以是一个线程，同一消费组的消费者可以在同一台机器也可以在不同的台机器。

对于消息中间件而言一般有两种投递模式：点对点(Point-to-Point)模式和发布订阅(Pub/Sub)模式。点对点模式是基于队列的，消息生产者发送消息到消息队列，消费者从消息队列中接收消息；发布订阅模式中生产者将消息发布到主题，订阅主题的消费者都能接收到消息。Kafka 支持这两种投递模式：

- 如果所有消费者都隶属于同一个消费组，那么所有的消息都被均匀地投递到每一个消费者，即每条消息只会被一个消费者处理，相当于点对点模式
- 如果所有的消费者都隶属于不同的消费组，那么所有的消息都会被广播给所有的消费者，即每条消息都会被所有的消费者处理，相当于发布/订阅模式

### 消息消费

### 分区分配

### 反序列化

### 拦截器

### 消费位移

Kafka 分区的每个消息都有唯一的 offset 表示消息在分区中的位置，消费者在拉取到消息处理完成之后需要向 broker 提交分区下次 poll 的起始消息的 offset，即 poll 拉取的最后一条消息的 offset + 1。Kafka 将消费者提交的 offset 持久化到内部主题 ```__consumer_offsets``` 中，消费者在向 broker 拉取数据时，broker 在 ```__consumer_offsets``` 中获取拉取的起始消息位置。

####  位移提交

#### `__consumer_offset`
位移提交的内容最终会保存到 Kafka 的内部主题 __consumer_offsets 中。一般情况下，当集群中第一次有消费者消费消息时会自动创建主题 __consumer_offsets，副本因子可以通过 ```offsets.topic.replication.factor``` 参数设置，分区数可以通过 ```offsets.topic.num.partitions``` 参数设置

客户端提交消费位移是使用 OffsetConmmitRequest 请求实现的，OffsetCommitRequest 的结构如下：
- group_id
- generation_id
- member_id
- retention_time 表示当前提交的消费位移所能保留的时长，通过 ```offsets.retention.minutes``` 设置
- topics

最终提交的消费位移会以消息的形式发送到主题 __consumer_offsets，与消费位移对应的消息也只定义了 key 和 value 字段的具体内容，它不依赖于具体版本的消息格式，以此做到与具体的消息格式无关。

在处理完消费位移之后，Kafka 返回 OffsetCommitResponse 给客户端，OffsetCommitResponse 的结构如下：
```java
```
可以通过 ```kafka-console-consumer.sh``` 脚本来查看 __consumer_offsets 中的内容：
```shell
```
如果有若个案消费者消费了某个主题的消息，并且也提交了相应的消费位移，那么在删除这个主题之后会一并将这些消费位移信息删除。

### 组协调器


### 消费者管理

### 参数调优

- ```fetch.min.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最小数据量，默认为 1B。Kafka 在收到 KafkaConsumer 的拉取请求时如果数据量小于这个值时需要等待直到足够为止，因此如果设置过大则可能导致一定的延时
- ```fetch.max.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最大数据量，默认为 52428800B(50MB)。
- ```fetch.max.wait.ms```：设置 KafkaConsumer 阻塞等待的时间，如果 Kafka 的数据量小于拉取的最小数据量则阻塞等待直到超过这个时间，可适当调整以避免延时过大
- ```max.partition.fetch.bytes```：用于配置从每个分区一次返回给 Consumer 的最大数据量，默认为 1048576B(1MB)。而 ```fetch.max.bytes``` 是一次拉取分区数据量之和的最大值
- ```max.poll.records```：设置 Consumer 在一次拉取中的最大消息数，默认 500。如果消息比较小可以适当调大这个参数来提升消费速度
- ```connection.max.idle.ms```：设置连接闲置时长，默认 540000ms(9 分钟)。闲置时长大于该值得连接将会被关闭
- ```exclude.internal.topics```：用于指定 Kafka 内部主题(__consumer_offsets 和 __transaction_state)是否可以向消费者公开，默认为 true，true 表示只能使用 subscribe(Collection) 的方式订阅
- ```receive.buffer.bytes```：设置 Socket 接收消息缓冲区(SO_RECBUF)的大小，默认为 5653B(64KB)，如果设置为 -1 表示使用操作系统的默认值
- ```send.buffer.bytes```：设置 Socket 发送消息缓冲区(SO_SENDBUF)的大小，默认为 131072B(128KB)，如果设置为 -1 表示使用操作系统的默认值
- ```request.timeout.ms```：设置 Consumer 请求等待的响应的最长时间，默认为 30000ms
- ```metadata.max.age.ms```：配置元数据的过期时间，默认值为 300000ms，如果元数据在限定时间内没有更新则强制更新即使没有新的 broker 加入
- ```reconnect.backoff.ms```：配置尝试重新连接指定主机之前的等待时间，避免频繁的连接主机，默认 50ms
- ```retry.backoff.ms```：配置尝试重新发送失败的请求到指定的主题分区之前等待的时间，避免由于故障而频繁重复发送，默认 100ms
- ```isolation.level```：配置消费者的事务隔离级别，可以为 "read_uncommiteed"，"read_committed"
- bootstrap.servers  ""    key.deserializer    消息 key 对应的反序列化类  value.deserializer    消息 value 对应的反序列化类  group.id  ""  消费者所属消费组的位移标识  client.id  ""  消费者 clientId  heartbeat.interval.ms  3000  分组管理时消费者和协调器之间的心跳预计时间，通常不高于 session.timeout.ms 的 1/3  session.timeout.ms  10000  组管理协议中用来检测消费者是否失效的超时时间  max.poll.interval.ms  300000  拉取消息线程最长空闲时间，超过此时间则认为消费者离开，将进行再均衡操作  auto.offset.reset  latest  有效值为 "earliest", "latest", "none"  enable.auto.commit  true  是否开启自动消费位移提交  auto.commit.interval.ms  5000  自动提交消费位移时的时间间隔  partition.assignment.strategy  RangeAssignor  消费者分区分配策略  interceptor.class  ""  消费者客户端拦截器
