## TransactionManager


### 事务
消息中间件的消息传输分为 3 个层级：
- at most once：至多一次，消息可能会丢失，但绝不会重复传输
- at least once：至少一次，消息绝对不会丢失，但可能会重复传输
- exactly once：恰好一次，每条消息肯定会传输一次且仅传输一次
当生产者向 Kafka 发送消息时，一旦消息被成功提交到日志文件，由于多副本机制的存在消息就不会丢失。如果生产者发送消息到 Kafka 是遇到网络问题则生产者无法判断消息是否已经发送成功，通过重试可以保证消息发送到 Kafka，但是重试可能会引起消息重入写入，所以对于生产者而且 Kafka 提供的消息保障为 at-least-once；对于消费者而言如果在拉取消息并处理完之后提交消费位移，那么可能出现在消费位移提交之前消费者故障导致消息重复消费，此时对应 at-least-once，如果拉取消息后直接提交消费位移则可能出现消费者在消费消息之前故障导致消息丢失，此时对应 at-most-once。

#### 幂等
生产者在进行重试的时候可能会重复写入消息，使用 Kafka 的幂等功能之后可以避免这种情况。开启幂等功能需要显式地将生产者客户端参数```enable.idempotence``` 设置为 true(默认为 false)。使用幂等功能时需要保证 retries 参数不小于 0，否则会抛出 ConfigException，如果没有设置则默认设置为 Integer.MAX_VALUE；同时还需要保证 max.in.flight.requests.per.connection 参数的值不能大于 5，否则也会抛出 ConfigException，如果显示设置了 acks 参数，那么 acks 参数值为 -1，如果没有显示设置则默认设置为 -1。

为了实现生产者的幂等性，Kafka 引入了生产者 Id(pid) 和序列号(sequence number)这两个概念，每个生产者实例在初始化的时候都会分配一个对用户透明的 pid，对于每个 pid 消息发送到每个分区都有对应的序列号，这些序列号从 0 开始单调递增的。生产者每发送一条消息就会将 ```<pid, 分区>``` 对应的序列号的值加 1

broker 端会在内存中为每一对 ```<pid, 分区>``` 维护一个序列号，对于收到的每一条消息只有当序列号的值(sn_new)比 broker 端中维护的对应的序列号的值(sn_old)大 1，即 sn_new=sn_old 时 broker 才会接收，如果 sn_new < sn_old+1 则表明消息重复写入，broker 会直接将消息丢弃，如果 sn_ndw > sn_old+1，那么说明中间有数据尚未写入，出现了乱序则会抛出 outOfOrderSequenceException。

引入消息幂等只是针对每一对```<pid, 分区>```而言，也就是说 Kafka 幂等只能保证单个生产者会话中单分区幂等。Kafka 并不保证消息内容的幂等
#### 事务
事务可以保证对多个分区写入操作的原子性。Kafka 中的事务可以使应用程序将消息消费、生产消息、提交消费位移当作原子操作来处理，同时成功或失败，即使该生产或消费会跨多个分区。

为了实现事务，应用程序必须提供唯一的 transactionalId，这个 transactionalId 通过客户端参数 transactional.id 来显示设置，事务要求生产者开启幂等，因此也需要将 enable.idempotence 设置为 true。

transacionalId 和 PID 一一对应，为了保证新的生产者启动后具有相同 transactionalId 的旧生产者立即失效，每个生产者通过 transactionalId 获取 PID 的同时还会获取一个单调递增的 producer epoch，如果使用同一个 transactionalId 开启两个生产者，那么前一个启动的生产者会抛出错误。

从生产者分析，通过事务 Kafka 可以保证跨生产者会话的消息幂等发送以及跨生产者会话的事务恢复，具有相同 transactionalId 的新生产者实例被创建且工作的时候，旧的且拥有相同 transactionalId 的生产者实例将不再工作；当某个生产者宕机后，新的生产者实例可以保证任何未完成的旧事务要么被提交(Commit)，要么被终止(Abort)。

从消费者分析，Kafka 并不能保证已提交的事务中的所有消息都能够被消费：
- 对采用日志压缩策略的主题而言，事务中的某些消息可能被清理
- 事务中消息可能分布在同一个分区的多个日志分段中，当老的日志分段被删除时，对应的消息可能会丢失
- 消费者可以通过 seek 方法访问任意 offset 的消息，从而可能遗漏事务中的消息
- 消费者在消费时可能没有分配到事务内的所有分区，因此也就不能读取事务中的所有消息

KafkaProducer 提供了 5 个与事务相关的方法：
```java
public void initTransactions()

public void beginTransaction()

public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
                                         String consumerGroupId)

public void commitTransaction()

public void abortTransaction()
```
- initTransactions 方法用于初始化事务，这个方法能够执行的前提是配置了 transactionId，如果没有则抛出 IllegalStateException
- beginTransaction 方法用于开启事务
- sendOffsetsToTransaction 方法为消费者提供在事务内的位移提交操作
- commitTransaction 方法用于提交事务 
- abortTransaction 方法用于中止事务

```java
Properties properties = new Properties();
properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
properties.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, "transactionMsg");

KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
// 初始化事务
producer.initTransactions();
// 开启事务
producer.beginTransaction();
try{
    // 事务处理
    ProducerRecord<String, String> record1 = new ProducerRecord<>("transactionTopic", "transactionMsg1");
    producer.send(record1);
    ProducerRecord<String, String> record2 = new ProducerRecord<>("transactionTopic", "transactionMsg2");
    producer.send(record2);
    ProducerRecord<String, String> record3 = new ProducerRecord<>("transactionTopic", "transactionMsg3");
    producer.send(record3);
    
    // 提交事务
    producer.commitTransaction();
}catch (Exception e){
    // 中止事务
    producer.abortTransaction();
}
producer.close();
```
消费端有一个参数 isolation.level 用于设置事务的隔离级别，默认是 read_uncommitted，即表示消费端应用可以看到未提交的事务内的消息。如果设置为 read_committed 则表示消费端应用看不到未提交的事务内的消息，但是 KafkaConsumer 内部会缓存这些消息，直到生产者执行 commitTransaction 之后将消息推送给消费端应用或者 abortTransaction 方法之后将消息丢弃。

日志文件中除了普通的消息，还有一种消息专门用来标识一个事务的结束，它就是控制消息(ControlBatch)。控制消息一共有两种类型：COMMIT 和 ABORT，分别用来表征事务已经成功提交或者已经被成功中止。KafkaConsumer 可以通过这个控制器消息来判断对应的事务是被提交了还是中止了，然后结合参数 isolation.level 配置的隔离级别来决定是否将相应的消息返回给消费端应用，ControlBatch 对消费端应用不可见。

```java
public static final String brokers = "localhsot:9092";
public static final String transactionId = "consume_transform_produce";

public static Properties consumerProperties() {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "groupId");

    return properties;
}

public static Properties producerProperties() {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    properties.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, transactionId);

    return properties;
}

public static void transactionConsumeTransformProduce() {
    // 初始化生成者和消费者
    KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties());
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
    consumer.subscribe(Collections.singleton("consum_transform_produce_topic"));
    // 初始化事务
    producer.initTransactions();
    while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
        if (!records.isEmpty()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            // 开启事务
            producer.beginTransaction();
            try {
                for (TopicPartition tp : records.partitions()) {
                    // 每个分区的消息
                    List<ConsumerRecord<String, String>> pr = records.records(tp);
                    for (ConsumerRecord<String, String> record : pr) {
                        // 处理消息
                        // ...

                        // 消息处理完之后重新发送到 Kafka
                        ProducerRecord<String, String> producerRecord = new ProducerRecord<>("topic-sink", record.key(), record.value());
                        producer.send(producerRecord);
                    }
                    long lastConsumerOffset = pr.get(pr.size() - 1).offset();
                    offsets.put(tp, new OffsetAndMetadata(lastConsumerOffset + 1));
                }
                // 提交消费位移
                producer.sendOffsetsToTransaction(offsets, "groupId");
                // 提交事务
                producer.commitTransaction();
            } catch (Exception e) {
                // 中止事务
                producer.abortTransaction();
            }
        }

    }
}
```
注意：在使用 KafkaConsumer 的时候要将 enable.auto.commit 参数设置为 false，并且不能手动提交消费位移。

#### 事务协调器
为了实现事务的功能，Kafka 引入了事务协调器(TransactionCoordinator) 来负责处理事务。每一个生产者都会被指派一个特定的 TransactionCoordinator，所有的事务逻辑都是由 TransactionCoordinator 来负责实施。TransactionCoordinator 会将事务状态持久化到内部主题 __transaction_state 中。

以 consume-transform-produce 流程为例分析：
##### 查找 TransactionCoordinator
TransactionCoordinator 负责分配 PID 和管理事务，因此生产者要做的第一件事就是找出对应的 TransactionCoordinator 所在的 broker 节点。通过 FindCoordinatorRequest 请求来实现，FindCoordinatorRequest 中的 coordinator_type 为 1 表示与事务相关。

Kafka 在收到 FindCoordinatorRequest 请求之后，会根据 coordinator_key 查找相应的 TransactionCoordinator 节点，如果找到则会返回其相对应的 node_id, host 和 port 信息。具体查找 TransactionCoordinator 的方式是根据 transactionId 的哈希值计算主题 __transaction_state 中的分区编号：
```java
Utils.abs(transactionId.hasCode() % transactionTopicPartitionCount)
```
其中 transactionTopicPartitionCount 为主题 __transaction_state 中的分区个数，这个可以通过 broker 端参数 transaction.state.log.num.partitions 来配置，默认是 50

找到对应的分区之后，再寻找次分区 leader 副本所在的 broker 节点，改 broker 节点即为这个 transactionId 对应的 TransactionCoordinator 节点
##### 获取 PID
找到 TransactionCoordinator 节点之后需要为当前生产者分配一个 PID。凡是开启了幂等性功能的生产者都必须执行这个操作，不需要考虑该生产者是否还开启了事务。生产者获取 PID 的操作是通过 InitProducerIdRequest 请求来实现，InitProducerIdRequest 的请求体结果如下：
- transaction_id 表示事务的 transactionId
- transaction_time_out 表示 TransactionCoordinator 等待事务状态更新的超时时间，通过生产者客户端参数 transaction.timeout.ms 配置

生产者的 InitProducerIdRequest 请求会发送给 TransactionCoordinator，如果未开启事务而只是开启幂等，那么 InitProducerIdRequest 可以发送给任意的 broker。当 TransactionCoordinator 第一次收到包含该 transactionId 的 InitProducerIdRequest 请求时会把 transactionId 和对应的 PID 一消息的形式保存到主题 __transaction_state 中，这样可以保证 <transaction_id, PID> 的对应关系被持久化，从而保证即使 TransactionCoordinator 故障该对应关系也不会丢失。

与 InitProducerIdRequest 对应的 InitProducerIdResponse 除了返回 PID 外，还会触发执行以下任务：
- 增加该 PID 对应的 producer_epoch，具有相同 PID 但 producer_epoch 小于该 producer 的其他生产者新开启的事务将被拒绝
- Commit 或 Abort 之前生产者未完成的事务

##### 开启事务
调用生产者开启事务的方法后，生产者本地会标记已经开启了一个事务，只有在生产者发送第一条消息之后 TransactionCoordinator 才会认为该事务已经开启

##### Consume-Transform-Produce
当生产者给一个新的分区发送数据前，它需要先向 TransactionCoodinator 发送 AddPartitionsToTxnRequest 请求，这个请求会让 TransactionCoordinator 将 <transactionId, TopicPartition> 对应关系存储在主题 __transaction_state 中，有了这个对照关系之后就可以在后续的步骤中为每个分区设置 COMMIT 或 ABORT 标记，如果该分区是对应事务中的第一个分区，那么此时 TransactionCoordinator 还会启动对该事务的计时

生产者通过 ProduceRequest 请求发送消息到用户自定义主题中，和普通消息不同的是 ProducerBatch 中包含实质的 PID， producer_epoch 和 sequence number

通过 KafkaProducer 的 sendOffsetsToTransation 方法可以在I一个事务批次里处理消息的消费和发送，这个方法会向 TransactionCoordiantor 节点发送 AddOffsetsToTxnRequest 请求，TransactionCoordinator 收到这个请求之后会通过 groupId 来推导出在 __consumer_offsets 中的分区，之后 TransactionCoordinator 会将这个分区保存在 __transaction_state 中

sendOffsetsToTransation 在处理完 AddOffsetsToTxnRequest 之后，生产者还会发送 TxnOffsetCommitRequest 请求给 GroupCoordinator 从而将本次事务中包含的消费位移信息 offsets 存储到主题 __consumer_offsets 中

##### 提交或中止事务
一旦数据写入成功，就可以调用 commitTransaciton 方法或者 abortTransaction 方法来结束当前事务

无论调用 commitTransaction 方法还是 abortTransaction 方法生产者都会向 TransactionCoordinator 发送 EndTxnRequest 请求，以此通知它提交或中止事务。TransactionCoordinator 在收到 EndTxnRequest 请求之后会执行如下操作：
- 将 PREPARE_COMMIT 或 PREPARE_ABORT 消息写入主题 __transaction_state
- 通过 WriteTxnMarkersRequest 请求将 COMMIT 或 ABORT 信息写入用户所使用的普通主题 __consumer_offsets
- 将 COMPLETE_COMMIT 或 COMPLETE_ABORT 信息写入内部主题 __transaction_state

WriteTxnMarkersRequest 请求是由 TransactionCoordinator 发向事务中各个分区的 leader 节点，当节点收到这个请求之后会在相应的分区总写入控制消息，控制消息用来标识事务的终结。

TransactionCoordinator 将最终的 COMPLETE_COMMIT 或 COMPLETE_ABORT 信息写入 __transaction_state 以表明当前事务已经结束，此时可以删除主题 __transaction_state 中所有的关于事务的消息。由于主题 __transaction_state 采用的日志清理策略为日志压缩，所以这里的删除只需要将相应的消息设置为墓碑消息即可。