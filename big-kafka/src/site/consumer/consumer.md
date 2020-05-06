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



#### 消费者参数
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