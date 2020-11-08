### 消费者

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

[Back](../)