### 消费者

消费者从 broker 上订阅主题，并从主题中拉取消息。除了消费者(Consumer)，Kafka 还有消费组(Consumer Group)，每个消费者都有一个对应的消费组，当消息发布到主题后只会被投递给订阅了该主题的消费组中的一个消费者。

消费组是一个逻辑上的概念，每个消费者只隶属于一个消费组，每个消费组有一个固定的名称，消费者在进行消费前需要指定其所属的消费组的名称，通过 ```group.id``` 来配置；消费者并非逻辑上的概念，它可以是一个进程也可以是一个线程，同一消费组的消费者可以在同一台机器也可以在不同的台机器。

对于消息中间件而言一般有两种投递模式：点对点(Point-to-Point)模式和发布订阅(Pub/Sub)模式。点对点模式是基于队列的，消息生产者发送消息到消息队列，消费者从消息队列中接收消息；发布订阅模式中生产者将消息发布到主题，订阅主题的消费者都能接收到消息。Kafka 支持这两种投递模式：
- 如果所有消费者都隶属于同一个消费组，那么所有的消息都被均匀地投递到每一个消费者，即每条消息只会被一个消费者处理，相当于点对点模式
- 如果所有的消费者都隶属于不同的消费组，那么所有的消息都会被广播给所有的消费者，即每条消息都会被所有的消费者处理，相当于发布/订阅模式

### 编程模型
消费者逻辑包含几个步骤：
- 配置消费者客户端参数并根据参数创建消费者实例
- 消费者订阅主题
- 拉取消息并消费
- 提交消费位移
- 关闭消费者实例
```java
public class KafkaConsumerTest {

  private static String broker = "localhost:9092";
  private static String topic = "topic-demo";
  private static String group = "group-demo";

  private static volatile boolean isRunning = true;

  // 配置消费者参数
  public static Properties initConfig(){
    Properties props = new Properties();
    // bootstrap.servers 指定 broker 列表
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
    // group.id 指定消费组名称
    props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    // key.deserializer 指定 key 的反序列化器
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    // value.deserializer 指定 value 的反序列化器
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    // client.id 指定客户端 id
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, "client-id-demo");
    return props;
  }

  public static void main(String[] args) {
    Properties props = initConfig();
    // 初始化消费者实例
    KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
    // 订阅主题
    consumer.subscribe(Arrays.asList(topic));
    try{
      while (isRunning){
        // 拉取消息
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
        // 消费消息
        for (ConsumerRecord<String, String> record : records){
          System.out.println(record);
        }
        if (records.isEmpty()){
          isRunning = false;
        }
      }
    }catch (Exception e){
      e.printStackTrace();
    }finally{
      // 关闭消费者
      consumer.close();
    }
  }
}
```
#### 订阅主题与分区
一个消费者可以订阅多个主题，KafkaConsumer 提供了多个重载的方法用于订阅主题：
```java
// 以集合的方式订阅主题
subscribe(Collection<String> topics, ConsumerRebalanceListener listener)

// 以正则表达式的方式订阅主题
subscribe(Pattern pattern, ConsumerRebalanceListener listener)

// 订阅主题的特定分区
assign(Collection<TopicPartition> partitions)
```
消费者只能使用一种方式订阅主题，否则会抛出 IllegalStateException 异常。以集合的方式订阅主题时，多次订阅以最后一次为最终订阅的主题；以正则表达式订阅主题时新创建的主题匹配正则表达式也会被消费；订阅主题的特定分区时需要指定主题的特定分区 TopicPartition

TopicPartition 表示主题的分区，有两个属性 topic 和 partition 分别表示主题和分区：
```java
public final class TopicPartition implements Serializable {
  // 分区
	private final int partition;
  // 主题
	private final String topic;
}
```
使用 ```KafkaConsumer#partitionsFor(topic)``` 查看主题的元数据可以获取主题的分区信息(PartitionInfo)列表，包含主题分区、leader 副本位置，AR集合位置，ISR 集合位置等：
```java
public class PartitionInfo{
  // 主题
  private final String topic;
  // 分区号
  private final int partition;
  // 分区 leader 副本
  private final Node leader;
  // 分区所有副本(AR)
  private final Node[] replicas;
  // 分区 ISR
  private final Node[] inSynReplicas;
  // 分区 OSR
  private final Node[] offlineReplicas;

  // ...
}
```
结合 partitionsFor 和 assign 就可以订阅主题的指定分区：
```java
List<TopicPartition> partitions = new ArrayList<>();
List<PartitionInfo> prititionInfos = consumer.partitionsFor(topic);
if (partitionInfos != null) {
  for (PartitionInfor info : partitionInfos) {
    partitions.add(new TopicPartition(info.topic(), info.partition()));
  }
}
consumer.assign(partitions);
```
通过 subscribe 方法订阅的主题具有消费者自动再均衡的能力，也就是说在消费者发生变化时可以根据分区的分配策略自动的调整分区分配关系从而实现消费负载均衡以及故障自动转移，而 assign 方法由于指定了消费的分区从而没有自动再均衡的能力。

subscribe 方法有一个 ConsumerRebalanceListener 接口的参数，该接口会在发生再均衡的时候被调用，ConsumerRebalanceListener 接口有两个方法：
- ```onPartitionsRevoked(partitions)```：在再均衡开始之前和消费者停止拉取消息之后执行，可以通过这个回调方法来处理消费位移的提交以此来避免一些不必要的重复消费的问题，partitions 表示再均衡前所分配到的分区
- ```onPartitionsAssigned(partitions)```：在重新分配分区之后和消费者开始读取消息之前被调用，partitions 表示再均衡后分配到的分区
```java
Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();
consumer.subscribe(Arrays.asList(topic), new ConsumerRebalanceListener() {
  public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    // 再均衡发生之后同步提交消费位移，避免消费位移没有提交
    consumer.commitSync(currentOffsets);
    currentOffsets.clear();
  }
  public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      for (TopicPartition tp : partitions){
        // 再均衡之后从 DB 中查找分区上次的消费位移
        consumer.seek(tp, getOffsetFromDB);
      }
  }
});

try{
  while(isRunning){
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
    for(ConsumerRecord<String, String> record : records){
      currentOffsets.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
    }
    consumer.commitAsync(currentOffsets, null);
  }
}finally{
  consumer.close();
}
```
KafkaConsumer 提供了取消订阅的 ```unsubscribe``` 方法，这个方法可以取消 subscribe 和 assign 所有的订阅，如果订阅方法的参数为空则等同于取消订阅：
```java
// equals to subscribe()
public void unsubscribe()
```
#### 反序列化
KafkaConsumer 提供了多种反序列化器，且都实现了 Deserializer 接口，自定义反序列化器只需要实现 Deserializer 接口并实现方法，然后在消费者客户端配置相应参数即可：
```java
public class CustomDeserializer implements Deserializer<String> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {}

    @Override
    public String deserialize(String topic, byte[] data) { return new String(data); }

    @Override
    public void close() {}
}
```
需要在创建消费者客户端时指定反序列化类才能使自定义反序列化生效：
```java
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, CustomDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, CustomDeserializer.class.getName());
```
#### 消息消费
消息的消费模式一般有两种：推模式和拉模式，推模式是服务端主动将消息推送给消费者，拉模式是消费者主动向服务端发起请求拉取消息。Kafka 的消费是基于拉模式的，消费者不断轮询调用 poll 方法从服务端拉取订阅的消息，如果分区中没有消息就返回空消息集合。

poll 返回的是 ConsumerRecords，其中维护着 Map<TopicPartition, List<ConsumerRecord<K, V>>> 数据类型的消息，ConsumerRecord 是真正消费的消息，包含消费消息的各种信息：
```java
public class ConsumerRecord<K, V>{
  // 消息的主题
  private final String topic;
  // 消息的分区
  private final int partition;
  // 消息在所属分区的偏移量
  private final long offset;
  // 消息的时间戳
  private final long timestamp;
  // 消息的时间戳类型，CreateTime 或 LogAppendTime
  private final TimestampType timestampType;
  // 序列化后 key 的大小
  private final int serializedKeySize;
  // 序列化后 value 的大小
  private final int serializedValueSize;
  // 消息的 header
  private final Headers headers;
  // 消息的 key
  private final K key;
  // 消息的 value
  private final V value;
  private final Optional<Integer> leaderEpoch;
  // 消息的 CRC32 校验值
  private volatile Long checksum;

  // ...
}
```
ConsumerRecords 提供了 iterator 方法遍历集合获取每个 ConsumerRecord 来消费拉取的消息：
```java
public Iterator<ConsumerRecord<String, String>> it = records.iterator();
while(it.hasNext()){
  ConsumerRecord<String, String> record = it.next();
  // ...
}
```
除了遍历 ConsumerRecords 来一条条的消费消息外，ConsumerRecords 还提供了 partitions 方法获取消息集中的分区并提供 records 方法用于按照分区划分消息集：
```java
// 按分区分组拉取的消息
for(TopicPartition tp : records.partitions()){
	for (ConsumerRecord record : records.records(tp)){
		System.out.println(record);
	}
}

// 按主题分组拉取的消息
for (ConsumerRecord record : records.records(topic)){
	System.out.println(record);
}
```

ConsumerRecords 提供了 count 方法计算消息集中的消息数量，isEmpty 方法用于判断消息集是否为空
#### 控制或关闭消费
KafkaConsumer 提供了对消费者消费速度的控制，KafkaConsumer 提供 pause 方法和 resume 方法分别实现暂停某些分区的拉取操作和恢复某些分区的拉取操作：
```java
public void pause(Collection<TopicPartition> partitions)

public void resume(Collection<TopicPartition> partitions)
```
KafkaConsumer 还提供了 wakeup 方法退出 poll 方法的逻辑并抛出 WakeupException 异常，paused 方法查看被暂停的分区：
```java
public void wakeup() {
  this.client.wakeup();
}

public Set<TopicPartition> paused() {
  acquireAndEnsureOpen();
  try {
    return Collections.unmodifiableSet(subscriptions.pausedPartitions());
  } finally {
    release();
  }
}
```
KafkaConsumer 提供 close 方法关闭消费者并释放运行过程中占用的资源：
```java
private void close(long timeoutMs, boolean swallowException{
  // ...

  // 关闭消费者协调器
  if (coordinator != null)
    coordinator.close(time.timer(Math.min(timeoutMs, requestTimeoutMs)));
	
  // 关闭拦截器、序列化器、Metric 等相关资源
  ClientUtils.closeQuietly(fetcher, "fetcher", firstException);
  ClientUtils.closeQuietly(interceptors, "consumer interceptors", firstException);
  ClientUtils.closeQuietly(metrics, "consumer metrics", firstException);
  ClientUtils.closeQuietly(client, "consumer network client", firstException);
  ClientUtils.closeQuietly(keyDeserializer, "consumer key deserializer", firstException);
  ClientUtils.closeQuietly(valueDeserializer, "consumer value deserializer", firstException);
  AppInfoParser.unregisterAppInfo(JMX_PREFIX, clientId, metrics);

  //...
}
```
#### 消费者拦截器
消费者拦截器主要在拉取消息和提交消费位移的时候进行定制化处理，定义消费拦截器需要实现 ```org.apache.kafka.clients.consumer.ConsumerInterceptor``` 接口并重写方法：
- ```onConsume(records)```：KafkaConsumer 在 poll 方法返回之前调用来对消息进行定制化操作，onConsume 方法中的异常将会被捕获而不会向上传递
- ```onCommit(offsets)```：KafkaConsumer 在提交完消费位移之后调用来记录跟踪提交的位移信息
```java
public class CustomConsumerInterceptor implements ConsumerInterceptor<String, String> {

    private static final long EXPIRE_INTERVAL = TimeUnit.SECONDS.toMillis(10);
    @Override
    public ConsumerRecords<String, String> onConsume(ConsumerRecords<String, String> records) {
        long now = System.currentTimeMillis();
        Map<TopicPartition, List<ConsumerRecord<String, String>>> newRecords = new HashMap<>();
        for(TopicPartition tp : records.partitions()){
            List<ConsumerRecord<String, String>> tpRecords = records.records(tp);
            List<ConsumerRecord<String, String>> newTpRecords = new ArrayList<>();
            for(ConsumerRecord<String, String> record : tpRecords){
                if (now - record.timestamp() < EXPIRE_INTERVAL){
                    newTpRecords.add(record);
                }
            }
            if (!newTpRecords.isEmpty()){
                newRecords.put(tp, newTpRecords);
            }
        }
        return new ConsumerRecords<>(newRecords);
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((tp, offset) -> System.out.println(tp + ":" + offset.offset()));
    }

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
```
在创建消费者实例时指定配置才能使自定义拦截器生效：
```java
props.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, CustomConsumerInterceptor.class.getName());
```
#### 多线程消费
KafkaConsumer 是非线程安全的，每个 KafkaConsumer 都维护着一个 TCP 连接，可以通过每个线程创建一个 KafkaConsumer 实例实现多线程消费：
```java
public class MultiThreadKafkaConsumer {

    public static final String broker = "localhost:9092";
    public static final String topic = "topic-demo";
    public static final String groupId = "group-demo";

    public static Properties initConfig(){
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "client-demo");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        return props;
    }
    public static void main(String[] args) {

        Properties props = initConfig();
        int consumerThreadNum = 4;
        for (int i = 0; i < consumerThreadNum; i++){
            new KafkaConsumerThread(props, topic).start();
        }
    }

    public static class KafkaConsumerThread extends Thread{

        private KafkaConsumer<String, String> kafkaConsumer;
        public KafkaConsumerThread(Properties props, String topic){
            this.kafkaConsumer = new KafkaConsumer<>(props);
            kafkaConsumer.subscribe(Arrays.asList(topic));
        }

        @Override
        public void run() {
            try{
                while (true){
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> record : records){
                        // process
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
            }finally {
                kafkaConsumer.close();
            }
        }
    }
}
```
因为每个 KafkaConsumer 都维护一个 TCP 连接，当 KafkaConsumer 比较多时会造成比较大的开销。可以只实例化一个 KafkaConsumer 作为 I/O 线程，另外创建线程处理消息：
```java
public class ThreadPoolKafkaConsumer {

    public static final String broker = "localhost:9092";
    public static final String topic = "topic-demo";
    public static final String groupId = "group-demo";
    public static final int threadNum = 10;
    private static final ExecutorService pool = new ThreadPoolExecutor(
        threadNum,
        threadNum,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1024),
        new ThreadPoolExecutor.AbortPolicy());

    public static Properties initConfig() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "client-demo");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        return props;
    }

    public static void main(String[] args) {
        Properties props = initConfig();
        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(props);
        consumer.subscribe(Arrays.asList(topic));
        try{

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                if (!records.isEmpty()){
                    pool.submit(new RecordHandler(records));
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            consumer.close();
        }
    }

    public static class RecordHandler extends Thread{
        public final ConsumerRecords<String, String> records;
        public RecordHandler(ConsumerRecords<String, String> records){
            this.records = records;
        }

        @Override
        public void run() {
            // process
        }
    }
}
```
使用线程池的方式可以使消费者的 IO 线程和处理线程分开，从而不需要维护过多了 TCP 连接，但是由于是线程池处理消息因此不能保证消息的消费顺序，可以使用一个额外的变量保存消息的 offset，在 KafkaConsumer 拉取消息之前先提交 offset，这在一定程度上可以避免消息乱序，但是有可能造成消息丢失(某些线程处理较小 offset 失败而较大 offset 处理成功则较小 offset 消息丢失，可保存处理失败的 offset 之后同一再次处理)；另外一种思路就是采用滑动窗口的思想 KafkaConsumer 每次拉取一些数据保存在缓存中，处理线程处理缓存中的消息直到全部处理成功。


### 删除消息
当分区创建的时候起始位置(logStartOffset)为0，可以使用 ```KafkaConsumer#beginningOffsets``` 方法查看分区的起始位置。使用 ```kafka-delete-records.sh``` 脚本来删除部分消息，在执行消息删除之前需要配置执行删除消息的分区及位置的配置文件：
```shell
delete.json
{
    "partitions"[
        {"topic":"topic-monitor","partition":0,"offset":10},
        {"topic":"topic-monitor","partition":1,"offset":11},
        {"topic":"topic-monitor","partition":2,"offset":12}
    ],
    "versions":1
}

bin/kafka-delete-records.sh --bootstrap-server localhost:9092 --offset-json-file delete.json
```
### 消费组管理
在 Kafka 中可以通过 ```kafka-consumer-groups.sh``` 脚本查看或变更消费组信息，通过 list 指令列出当前集群中所有的消费组：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```
通过 describe 指令可以查看指定消费组的详细信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \ 
--describe --group groupIdMonitor
```
其中 TOPIC 表示消费组订阅的主题，PARTITION 表示主题对应的分区号，CURRENT-OFFSET 表示消费组最新提交的消费位移，LOG-END-OFFSET 表示的是 HW，LAG 表示消息滞后的数量，CUNSUMER_ID 表示消费组的成员 ID，HOST 表示消费者 host，CLIENT_ID 表示消费者 clientId

消费组一共有 Dead、Empty、PreparingRebalance、Stable 这几种状态，正常情况下一个具有消费者成员的消费组的状态为 Stable，可以使用 state 参数查看消费组状态：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --state
```
如果消费组内没有消费者则消费组为 Empty 状态，可以通过 members 参数列出消费组内的消费者成员信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members
```
使用 verbose 参数可以查看每个消费者成员的分配情况：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members --verbose
```
使用 delete 指令删除指定的消费组，如果消费组中有消费者正在运行则会删除失败：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--delete --group groupIdMonitor
```

### 分区分配策略
Kafka 提供了消费者客户端参数 ```partition.assignment.strategy``` 参数设置消费者与订阅主题之间的分区分配策略，默认情况下是 ```org.apache.kafka.clients.consumer.RangeAssignor```，除此之外 Kafka 还提供 RoundRobinAssignor 和 StickyAssignor 两种分区分配策略，消费者客户端的分区策略参数可以配置多种分区分配策略。
#### RangeAssignor
RangeAssignor 分配策略是基于单个主题的，对于每个主题将分区按照数字顺序排序，将消费者按照字典顺序排序，然后将分区数除以消费者总数以确定要分配给每个消费者的分区数并将分区分配给对应的消费者。如果没有均匀分配，即分区数没有被消费者总数整除，那么前面的消费者将会多分配一个分区。

例如：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p1、t1p0、t1p1，C1 分配到的分区为 t0p2、t1p2
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for (String memberId : subscriptions.keySet())
        assignment.put(memberId, new ArrayList<>());

    for (Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()) {
        String topic = topicEntry.getKey();
        List<String> consumersForTopic = topicEntry.getValue();

        Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
        if (numPartitionsForTopic == null)
            continue;

        Collections.sort(consumersForTopic);

        int numPartitionsPerConsumer = numPartitionsForTopic / consumersForTopic.size();
        int consumersWithExtraPartition = numPartitionsForTopic % consumersForTopic.size();

        List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
        for (int i = 0, n = consumersForTopic.size(); i < n; i++) {
            int start = numPartitionsPerConsumer * i + Math.min(i, consumersWithExtraPartition);
            int length = numPartitionsPerConsumer + (i + 1 > consumersWithExtraPartition ? 0 : 1);
            assignment.get(consumersForTopic.get(i)).addAll(partitions.subList(start, start + length));
        }
    }
    return assignment;
}
```
#### RoundRobinAssignor
RoundRobinAssignor 分配策略列出所有可用分区和所有可用消费者，然后从分区到消费者进行循环分配。如果所有消费者订阅的主题相同那么分区的分配是均匀的，如果消费者订阅的主题不同则未订阅主题的消费者跳过而不分配分区。
- 消费者订阅相同：有两个消费者 C0 和 C1 都订阅了主题 t0 和 t1，每个主题有 3 个分区，也就是 t0p0、t0p1、t0p2、t1p0、t1p1、t1p2。那么 C0 分配到的分区为 t0p0、t0p2、t1p1，C1 分配到的分区为 t0p1、t1p0、t1p2
- 消费者订阅不同：有三个消费者 C0, C1 和 C2，三个主题 t0、t1 和 t2，分别有 1, 2, 3 个分区，也就是 t0p0、t1p0、t1p1、t2p0、t2p1、t2p2，C0 订阅了 t0，C1 订阅了 t0 和 t1，C2 订阅了 t0、t1、t2。那么 C0 分配到的分区为 t0p0，C1 分配到的分区为 t1p0，C2 分配到的分区为 t1p1、t2p0、t2p1、t2p2
```java
public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
                                                Map<String, Subscription> subscriptions) {
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for (String memberId : subscriptions.keySet())
        assignment.put(memberId, new ArrayList<>());

    CircularIterator<String> assigner = new CircularIterator<>(Utils.sorted(subscriptions.keySet()));
    for (TopicPartition partition : allPartitionsSorted(partitionsPerTopic, subscriptions)) {
        final String topic = partition.topic();
        while (!subscriptions.get(assigner.peek()).topics().contains(topic))
            assigner.next();
        assignment.get(assigner.next()).add(partition);
    }
    return assignment;
}
```
#### StickyAssignor
StickyAssignor 分区策略有两个目的：
- 分区的分配要尽可能均匀，即消费者分配到的分区数量相差最多只有 1 个
- 当发生分区重分配时，分区的分配尽可能与之前的分配保持同步
  
例如有三个消费者 C0, C1 和 C2 都订阅了四个主题 t0, t1, t2, t3 并且每个主题有两个分区，即 t0p0, t0p1, t1p0, t1p1, t2p0, t2p1, t3p0, t3p1。那么 C0 分配到 t0p0, t1p1, t3p0，C1 分配到 t0p1, t2p0, t3p1，C2 分配到 t1p0, t2p1。如果消费者 C1 故障导致消费组发生再均衡操作，此时消费分区会重新分配，则 C0 分配到 t0p0, t1p1, t3p0, t2p0 而 C2 分配到 t1p0, t2p1, t0p1, t3p1

#### 自定义分区分配策略
自定义分区分配策略需要实现 ```org.apache.kafka.clients.consumer.internal.PartitionAssignor``` 接口或者继承 ```org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor``` 类。

PartitionAssignor 接口中定义了两个内部类：Subscription 和 Assignment。

Subscription 用来表示消费者的订阅信息，topics 表示消费者的订阅主题列表，userData 表示用户自定义信息：
```java
class Subscription {
  private final List<String> topics;
  private final ByteBuffer userData;
  // ...
}
```
PartitionAssignor 接口通过 subscription 方法来设置消费者自身相关的 Subscription 信息。

Assignment 类用来表示分配结果信息，partition 表示所分配到的分区集合，userData 表示用户自定义的数据：
```java
class Assignment {
  private final List<TopicPartition> partitions;
  private final ByteBuffer userData;
  // ...
}
```
PartitionAssignor 接口中的 onAssignment 方法是在每个消费者收到消费组 leader 分配结果时的回调函数。

真正的分区分配方案的实现是在 assign 方法中，方法中的参数 metadata 表示集群的元数据信息，而 subscriptions 表示消费组内各个消费者成员的订阅信息，方法返回各个消费者的分配信息。

```java
public class RandomAssignor extends AbstractPartitionAssignor{
  public String name(){
    return "random";
  }
  public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic, Map<String, Subscription> subscriptions){
    Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
    Map<String, List<TopicPartition>> assignment = new HashMap<>();
    for(String memberId : subscriptions.keySet()){
      assignment.put(memberId, new ArrayList<>());
    }

    for(Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()){
      String topic = topicEntry.getKey();
      List<String> consumersForTopic = topicEntry.getValue();
      int consumerSize = consuemrForTopic.size();

      Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
      if(numPartitionsForTopic == null){
        continue;
      }

      List<TopicPartition> partitions = AbstractPartitionAssinor.partitions(topic, numPartitionsForTopic);
      for(TopicPartition partition : partitions){
        int rand = new Random().nextInt(consumerSize);
        String randomConsumer = consumersForTopic.get(rand);
        assignment.get(randomConsumer).add(partition);
      }
    }
    return assignment;
  }

  private Map<String, List<String>> consumersPerTopic(Map<String, Subscription> consumerMetadata){
    Map<String, List<String>> res = new HashMap<>();
    for(Map.Entry<String, Subscription> subscriptionEntry : consumerMetadata.entrySet()){
      String consumerId = subscriptionEntry.getKey();
      for(String topic : subscriptionEntry.getValue().topics()){}
        put(res, topic, consuemrId)
    }
    return res;
  }
}
```
在使用自定义分区分配时，需要在消费者客户端添加配置参数：
```java
properties.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, RandomAssignor.class.getName());
```
通过自定义分区策略可以使一个分区分配给同一消费组内的多个消费者，继而实现消费组内广播的功能：
```java
public class BroadcastAssigner extends AbstractPartitionAssinger{
  
}
```
### 再均衡

再均衡是指分区的所属权从一个消费者转移到另一个消费者的行为，它为消费组具备高可用性和伸缩性提供保障，使得可以方便安全的删除或添加消费组中的消费者。

再均衡发生期间消费组内的消费者是无法消费消息的，也就是在再均衡发生期间消费组不可用；再均衡之后会丢失消费者对分区持有的状态(如消费位移)。

触发再均衡的情况：
- 新的消费者加入消费组
- 消费者下线，消费者遇到长时间 GC、网络延时导致消费者长时间未向 GroupCoordinator 发送心跳等情况时会被认为下线
- 消费者退出消费组(发送 LeaveGroupRequest 请求)，比如消费者客户端调用 unsubscribe 发那个发取消订阅
- 消费组对应的 GroupCoordinator 节点发生变更
- 消费组内所有订阅的任一主题或者主题的分区数量发生变化

Kafka 服务端提供 GroupCoordinator 组件用于管理消费组，而消费者客户端的 ConsumerCoordinator 组件负责与 GroupCoordinator 进行交互。

当有消费者加入消费组时，消费者、消费组和组协调器之间会经历四个阶段：
##### 第一阶段(FIND_COORDINATOR)
消费者需要确定它所属的消费组对应的 GroupCoordinator 所在的 broker，并创建与该 broker 相互通信的网络连接。如果消费者已经保存了与消费组对应的 GroupCoordinator 节点的信息，并且与它之间的网络连接是正常的，那么就可以进入第二阶段，否则就需要向集群中的负载最小的节点发送 FindCoordinatorRequest 请求来查找对应的 GroupCoordinator。

FindCoordinatorRequest 请求体中只有两个域：coordinator_key 和 coordinator_type。coordinator_key 在这里就是消费组的名称 groupId，coordinator_type 设置为 0。Kafka 在收到 FindCoordinatorRequest 请求之后会根据 coordinator_key 查找对应的 GroupCoordinator 节点，如果找到对应的 GroupCoordinator 则会返回其相对应的 node_id、host 和 port 信息。

查找 GroupCoordiantor 的方式是先根据消费组 groupId 的哈希值计算 __consumer_offset 中的分区编号：```Utils.abs(groupId.hashCode) % groupMetadataTopicPartitionCount```，groupMetadataTopicPartitionCount 为主题 __consumer_offsets 的分区个数，可以通过 broker 端参数 offsets.topic.num.partitions 来配置，默认值是 50。

找到对应的 __consumer_offsets 中的分区之后，再寻找此分区 leader 副本所在的 broker 节点，该 broker 节点即为这个 groupId 所对应的 GroupCoordinator 节点。消费者 groupId 最终的分区分配方案及组内消费者所提交的消费位移信息都会发送给此分区 leader 副本所在的 broker 节点，让此 broker 节点既扮演 GroupCoordinator 的角色，又扮演保存分区分配方案和组内消费者位移的角色，这样可以省去很多不必要的中间轮转所带来的开销。
##### 第二阶段(JOIN_GROUP)
在成功找到消费组所对应的 GroupCoordiantor 之后就进入了加入消费组的阶段，在此阶段的消费者会向 GroupCoordinator 发送 JoinGroupRequest 请求并处理响应。

JoinGroupRequest 的请求体包含多个域：
- group_id 是消费组的 id
- session_timeout 对应消费端参数 ```session.timeout.ms```，GroupCoordinator 超过设置的时间内没有收到心跳报文则认为此消费者已经下线
- rebalance_timeout 对应消费端参数 ```max.poll.interval.ms```，表示当消费者再平衡的时候，GroupCoordinator 等待各个消费者重新加入的最长等待时间
- member_id 表示 GroupCoordinator 分配给消费者的 id 标识。第一次发送 JoinGroupRequest 请求的时候此字段设置为 null
- protocol_type 表示消费组实现的协议，对于消费者而言此字段值为 consumer

JoinGroupRequest 中的 group_protocol 域为数组类型，其中可以囊括多个分区分配策略，这个主要取决于消费者客户端参数 ```partition.assignment.strategy``` 的配置。如果配置了多种策略，那么 JoinGroupRequest 请求中就会包含多个 protocol_name 和 protocol_metadata。protocol_name 对应 PartitionAssignor 接口中 name 方法设置的值，protocol_metadata 是一个 byte 类型，其实质还可以更细粒度地划分为 version、topic 和 user_data。

version 占 2 个字节，目前其固定值为 0；topics 对应的 PartitionAssignor 接口的 subscription 方法返回值类型 Subscription 中的 topics，代表一个主题列表；user_data 对应 Subscription 中的 userData 可以为空。

如果是原有的消费者重新加入消费组，那么在真正发送 JoinGrouupRequest 请求之前还要执行一些准备工作：
- 如果消费端参数 enable.auto.commit 设置为 ture(默认为 true)，即开启自动提交位移功能，那么在请求加入消费组之前需要向 GroupCoordinator 提交消费位移。这个过程是阻塞执行的，要么成功提交消费位移，要么超时
- 如果消费者添加了自定义的再均衡监听器(ConsumerRebalanceListener)，那么此时会调用 onPartitionsRevoked 方法在重新加入消费组之前实施自定义的规则逻辑
- 因为是重新加入消费者组，之前与 GroupCoordinator 节点之间的心跳检测也就不需要了，所以在成功的重新加入消费组之前需要禁止心跳检测的运作

消费者在发送 JoinGroupRequest 请求之后会阻塞等待 Kafka 服务端的响应，服务端在收到 JoinGroupCoordinator 请求后会交由 GroupCoordinator 来进行处理，GroupCoordiantor 首先会对 JoinGroupRequest 进行合法性校验，如果消费者是第一次请求加入消费组，那么 JoinGroupRequest 请求中的 member_id 是 null，此时组协调器负责为此消费者生成一个 member_id，生成规则为 clientId 和 UUID 拼接而成

GroupCoordinator 需要为消费组内的消费者选举出一个 leader，选举算法为：如果组内还没有 leader 则第一个加入消费组的消费者即为 leader，如果 leader 退出导致的重新选举则从存储了消费者的 map 中选取第一个为 leader

leader 选取完毕之后需要选举分区分配策略，这个策略的选举是根据各个消费者支持的分区消费策略投票而决定，选举的过程如下：
- 收集各个消费者支持的所有分配策略组成候选集 candidates
- 每个消费者从后选举中找出第一个自身支持的策略并投票
- 计算候选集中各个策略的选票，选取选票最多的策略为当前消费组的分配策略

确定了消费组内的消费者 leader 和消费组的分区策略之后，Kafka 服务端发送 JoinGroupResponse 响应给各个消费者，leader 消费者和普通消费者的区别在于 leader 消费者的 members 字段包好消费组内消费者的成员信息包括选举出的分区分配策略。

##### 第三阶段(SYNC_GROUP)
leader 消费者根据选举出的分区分配策略实施具体的分区分配，在此之后需要将分配方案同步给各个消费者。leader 消费者并不是直接和其他消费者同步分配方法，而是通过 GroupCoordinator 实现分配方案同步。各个消费者向 GroupCoordinator 发送 SyncGrouupRequest 请求来同步分配方案，只有 leader 消费者发送的 SyncGrouupRequest 请求中包含具体的分区分配方案，这个分配方案保存在 group_assignment 中。

服务端在收到消费者发送的 SyncGroupRequest 请求之后会交由 GroupCoordinator 负责具体的逻辑处理。
GroupCoordinator 对 SyncGroupRequest 做合法校验之后将 leader 消费者发送的分配方案提取出来连同真个消费组的元数据信息一起存入 Kafka 的 ```__consumer_offsets``` 主题中，最后发送响应给各个消费者以提供各个消费者各自所属的分配方案。

当消费者收到所属的分配方案之后会调用 PartitionAssignor 中的 onAssignment 方法，随后再调用 ConsumerRebalanceListener 中的 onPartitionAssigned 方法，之后开启心跳任务，消费者定期向服务端的 GroupCoordinator 发送 HeartbeatRequest

##### 第四阶段(HEATBEAT)
在正式消费前，消费者需要确定拉取消息的起始位置，如果已经将最后的消费位移提交了 GroupCoordinator 并保存到了 __consumer_offsets 主题中，此时消费者可以通过 OffsetFetchRequest 请求获取上次提交的消费位移并从此处继续消费。

消费者向 GroupCoordinator 发送心跳来维持分区的所有权关系。心跳线程是一个独立的线程，如果消费者停止发送心跳的时间足够长则整个会话被判定为过期，GroupCoordinator 会认为这个消费者已经死亡，也就会触发一次再均衡行为。消费者心跳间隔由参数 ```heartbeat.interval.ms``` 指定，默认 3000。

如果消费者发送崩溃并停止读取消息，那么 GroupCoordiantor 会等待一段时间确认这个消费者死亡之后才会触发再均衡，这段时间由参数 ```session.timeout.ms``` 指定，这个参数必须配置在 broker 端参数 ```group.min.session.timeout.ms```(默认 6000) 和 ```group.max.session.timeout.ms```(默认 300000) 允许的范围内。

参数 ```max.poll.interval.ms``` 用于指定 poll 方法调用之间的最大延时，也就是消费者在获取更多消息之前可以空闲的时间最大值，如果超过此时间上限没有 poll 方法调用则任务消费者失败触发再均衡。

除了被动退出消费组，还可以向 GroupCoordinator 发送 LeaveGroupRequest 请求主动退出消费组，如在消费者客户端调用 unsubscribe 方法。

#### 消费者参数
- ```fetch.min.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最小数据量，默认为 1B。Kafka 在收到 KafkaConsumer 的拉取请求时如果数据量小于这个值时需要等待直到足够为止，因此如果设置过大则可能导致一定的延时
- ```fetch.max.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最大数据量，默认为 52428800B(50MB)。
- ```fetch.max.wait.ms```：设置 KafkaConsumer 阻塞等待的时间，如果 Kafka 的数据量小于拉取的最小数据量则阻塞等待直到超过这个时间，可适当调整以避免延时过大
- ```max.partition.fetch.bytes```：用于配置从每个分区一次返回给 Consumer 的最大数据量，默认为 1048576B(1MB)。而 ```fetch.max.bytes``` 是一次拉取分区数据量之和的最大值
- ```max.poll.records```：设置 Consumer 在一次拉取中的最大消息数，默认 500。如果消息比较小可以适当调大这个参数来提升消费速度
- ```connection.max.idle.ms```：设置连接闲置时长，默认 540000ms(9 分钟)。闲置时长大于该值得连接将会被关闭
- ```exclude.interval.topics```：用于指定 Kafka 内部主题(__consumer_offsets 和 __transaction_state)是否可以向消费者公开，默认为 true，true 表示只能使用 subscribe(Collection) 的方式订阅
- ```receive.buffer.bytes```：设置 Socket 接收消息缓冲区(SO_RECBUF)的大小，默认为 5653B(64KB)，如果设置为 -1 表示使用操作系统的默认值
- ```send.buffer.bytes```：设置 Socket 发送消息缓冲区(SO_SENDBUF)的大小，默认为 131072B(128KB)，如果设置为 -1 表示使用操作系统的默认值
- ```request.timeout.ms```：设置 Consumer 请求等待的响应的最长时间，默认为 30000ms
- ```metadata.max.age.ms```：配置元数据的过期时间，默认值为 300000ms，如果元数据在限定时间内没有更新则强制更新即使没有新的 broker 加入
- ```reconnect.backoff.ms```：配置尝试重新连接指定主机之前的等待时间，避免频繁的连接主机，默认 50ms
- ```retry.backoff.ms```：配置尝试重新发送失败的请求到指定的主题分区之前等待的时间，避免由于故障而频繁重复发送，默认 100ms
- ```isolation.level```：配置消费者的事务隔离级别，可以为 "read_uncommiteed"，"read_committed"

其他消费者参数：

|参数|默认值|含义|
|-|-|-|
|bootstrap.servers|""||
|key.deserializer||消息 key 对应的反序列化类|
|value.deserializer||消息 value 对应的反序列化类|
|group.id|""|消费者所属消费组的位移标识|
|client.id|""|消费者 clientId|
|heartbeat.interval.ms|3000|分组管理时消费者和协调器之间的心跳预计时间，通常不高于 session.timeout.ms 的 1/3|
|session.timeout.ms|10000|组管理协议中用来检测消费者是否失效的超时时间|
|max.poll.interval.ms|300000|拉取消息线程最长空闲时间，超过此时间则认为消费者离开，将进行再均衡操作|
|auto.offset.reset|latest|有效值为 "earliest", "latest", "none"|
|enable.auto.commit|true|是否开启自动消费位移提交|
|auto.commit.interval.ms|5000|自动提交消费位移时的时间间隔|
|partition.assignment.strategy|RangeAssignor|消费者分区分配策略|
|interceptor.class|""|消费者客户端拦截器|

[Back](../)