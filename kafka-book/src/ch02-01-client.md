# 客户端
`KafkaConsumer` 表示 Kafka 消费者客户端，在创建实例的时候需要指定集群的地址，消息的 key 和 value 的反序列化方式，以及消费者所属的消费组。
```java
// Broker 集群地址，不需要指定所有机器
properties.put("bootstrap.servers", "host:port");
// 消费组 id
properties.put("group.id", "group-id");
// key 反序列化器，需要和生产者序列化器对应
properties.put("key.deserializer", "key.deserializer.class.name");
// value 反序列化器，需要和生产者序列化器对应
properties.put("value.deserializer", "value.deserializer.class.name");
```
消费者客户端在消费消息后需要向集群提交消费位移，在多线程情况下不能保证消费位移的正确提交，因此消费者客户端不是线程安全的。

Kafka 采用发布/订阅(Pub/Sub)模型，即消费者在拉取消息之前需要订阅主题。`KafkaConsumer` 提供了三种重载的订阅主题的方法，不同的订阅方法不能混合使用，否则会抛出 `IllegalStateException` 异常：
```java
// 以集合的方式订阅主题
subscribe(Collection<String> topics, ConsumerRebalanceListener listener)

// 以正则表达式的方式订阅主题
subscribe(Pattern pattern, ConsumerRebalanceListener listener)

// 订阅主题的特定分区
assign(Collection<TopicPartition> partitions)
```
在订阅指定分区的时候，可以使用 `KafkaConsumer#partitionsFor(topic)` 方法获取元数据信息，其中包含了主题的分区信息。
```java
// 主题的分区信息
List<PartitionInfo> prititionInfos = consumer.partitionsFor(topic);
if(partitionInfos != null && partitionInfos.size() > 1){
    consumer.assign(Collections.singleton(new TopicPartition(topic, partitionInfos.get(0).partition)))
}
```
`KafkaConsumer` 订阅主题的方法需要传入回调参数 `ConsumerRebalanceListener`，在消费组中的消费者发生变化或者主题的分区数发生变化时，Kafka 会根据分区分配策略为每个订阅了主题的消费者重新分配消费分区，并且在消费分区发生变化时通过回调监听器来通知消费者客户端。`ConsumerRebalanceListener` 接口定义了两个方法：
```java
// 消费者停止拉取消息之后调用，通常会提交或者存储 offset 来避免重复消费
void onPartitionsRevoked(Collection<TopicPartition> partitions);

// 再均衡后消费者开始拉取消息前调用，通常会重新定位拉取消息的 offset
void onPartitionsAssigned(Collection<TopicPartition> partitions);
```
订阅了主题的消费者客户端可以通过 `KafkaConsumer#unsbuscribe()` 方法取消订阅，取消订阅后消费者拉取消息会抛出异常。取消订阅主题后消费者客户端的数量发生变化，会触发消费组内的消费者重新分配分区。

## 消息消费

消费者客户端订阅主题之后就可以从集群获取消息消费，消息的消费模式一般有两种：推模式和拉模式，推模式是服务端主动将消息推送给消费者，拉模式则是消费者主动向服务端发起请求拉取消息。

Kafka 消费者客户端的消息消费是基于拉模式的，消费者不断轮询调用 poll 方法从服务端拉取订阅的消息，如果分区中没有消息就返回空消息集合。
```java
// 等待 timeout 后如果没有消息则返回空集合
public ConsumerRecords<K, V> poll(final Duration timeout)
```
`KafkaConsumer` 拉取的消息是 `ConsumerRecords` 表示的集合，其中包含每个主题的消息列表，当主题没有新的消息时，对应的消息列表为空。消息列表中的对象是 `ConsumerRecord` 表示 Kafka 消费者客户端真正消费的消息：
```java
// 分区-消息集合
public class ConsumerRecords{
    private final Map<TopicPartition, List<ConsumerRecord<K, V>>> records;
}

// 消费者消息
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

}
```
`KafkaConsumer` 提供了对消息消费的控制，`pause(partitions)` 和 `resume(partitions)` 方法可以控制暂停和回复拉取指定分区的消息，通过 `paused()` 方法可以查看暂停拉取消息的分区。

### 多线程消费
`KafkaConsumer` 是非线程安全的，在下游消息消费业务比较耗时的情况下会降低消息消费的速率。

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


## 脚本工具

```shell script
# --bootstrap-server    集群地址
# --group-id            消费组
# --topic               订阅主题
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
--group-id  group-hello \
--topic topic-hello
```