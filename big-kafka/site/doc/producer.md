### 编程模型

生产者是负责向 Kafka 发送消息的客户端，Kafka 实现了多种语言的客户端。


生产者编程模型包含几个步骤：
- 配置生产者客户端参数并创建相应的生产者实例 
- 构建待发送的消息
- 发送消息
- 关闭生产者实例
```java
public class KafkaProducerTest{
    public static final String brokerList = "localhost:9092";
    public static final String topic = "topic-demo";

    // 初始化生产者参数
    public static Properties initConfig(){
        Properties props = new Properties();
        props.put("bootstrap.servers", brokerList);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.common.serialization.StringSerializer")
        props.put("client.id", "producer.client.id.demo")
        return props;
    }

    public static void main(String[] args){
        Properties props = initConfig();
        // 创建生产者
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        // 创建消息
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "hello kafka");
        try{
            // 发送消息
            producer.send(record);
        }catch(Exception e){
            e.printStackTrace();
        }
        // 关闭生产者
        producer.close();
    }
}
```
#### 配置生产者客户端参数
创建生产者实例前需要配置必要的参数：
- ```bootstrap.servers```：指定生产者客户端连接 Kafka 集群所需的 broker 地址列表，具体格式为 host1:prot1,host2:port2，这里不需要配置所有 broker 的地址因为生产者会从给定的 broker 里查找到其他 broker 的信息
- ```key.serializer``` 和 ```value.serializer```：指定消息的 key 和 value 的序列化类，broker 接收的消息必须以字节数组的形式存在，客户端在将消息发送到 broker 之前需要对消息进行序列化处理
- ```client.id```：指定客户端 id，如果不设置则会自动生成一个默认的 id

生产者客户端的初始化参数非常多，可以使用客户端提供的 ```org.apache.kafka.clients.producer.ProducerConfig```类来指定需要配置的参数：
```java
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName())
props.put(ProducerConfig.CLIENT_ID_CONFIG, "producer.client.id.demo")
```
#### 创建生产者客户端
KafkaProducer 是生产者的客户端，在创建 KafkaProducer 时可以通过 Map 也可以通过 Properties 传入必要的参数：
```java
public KafkaProducer(final Map<String, Object> configs)

public KafkaProducer(Properties properties)
```
**KafkaProducer 是线程安全的，可以在多个线程中共享单个实例，也可以将 Kafka 实例进行池化来供其它线程调用。**
#### 消息对象
消息对象 ProducerRecord 并不是单纯意义上的消息，它包含了多个属性：
- topic 和 partition 字段代表消息要发往的主题和分区号
- header 字段表示消息的头部，用于设置应用相关的信息
- key 用于指定消息的键，可用于消息分区的划分
- value 表示消息的内容
- timestamp 如果配置为 CreateTime 则表示消息创建的时间，如果配置为 LogAppendTime 则表示消息追加到日志文件的时间
```java
public class ProducerRecord<K, V>{
    private final String topic;
    private final Integer partition;
    private final Headers headers;
    private final K key;
    private final V value;
    private final Long timestamp;
	
    // ...
}
```
#### 发送消息
Kafka 消息通过 KafkaProducer 的 send 方法异步发送并且返回 Future 对象，send 方法有两个重载类型：
```java
Future<RecordMetadata> send(ProducerRecord<K, V> record);

Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback);
```
callback 在消息发送之后调用来实现消息异步发送确认，如果在创建生产者客户端时设置了重试参数 ```retries``` 则在发生可重试异常(RetriableException)时会自动重试，如果重试之后依然异常，则会抛出异常或者调用回调函数。对于同一个分区而言，消息的发送是有序的，callback 也是保证有序的：
```java
producer.send(msg, new Callback(){
    public void onCompletion(RecordMetadata metadata, Exception exception){
        // 表示发送消息异常
        if(exception != null){
            exception.printStackTrace();
        }else{
            System.out.println(metadata);
        }
    }
})
```
可以使用 Future 对象的 get 方法以阻塞的方式获取发送结果 RecordMetadata，该对象包含了消息的元数据信息：
- offset 表示消息在分区中的位移
- timestamp 如果配置为 LogAppendTime 则是 broker 返回的时间戳，如果配置为 CreateTime 则表示 producer 处理该 record 的本地时间戳
- serializedKeySize 表示序列化后的 key 字节数
- serializedVlaueSize 表示序列化后的 value 字节数
- topicPartition 表示发送消息的 Topic 和 partition 信息
- checksum 表示消息的 CRC32 校验和
```java
public final class RecordMetadata{
    private final long offset;
    private final long timestamp;
    private final int serializedKeySize;
    private final ing serializedVlaueSize;
    private final TopicPartition topicPartition;
    private volatile Long checksum;
	
    // ...
}
```
#### 关闭生产者客户端
消息发送完成之后调用 close 方法回收资源，close 方法会阻塞等待之前所有发送请求完成之后再关闭 KafkaProducer：
```java
// 等待关闭发送消息的线程
this.ioThread.join(timeoutMs);

// 清理拦截器、序列化器、分区器、监控
ClientUtils.closeQuietly(interceptors, "producer interceptors", firstException);
ClientUtils.closeQuietly(metrics, "producer metrics", firstException);
ClientUtils.closeQuietly(keySerializer, "producer keySerializer", firstException);
ClientUtils.closeQuietly(valueSerializer, "producer valueSerializer", firstException);
ClientUtils.closeQuietly(partitioner, "producer partitioner", firstException);
AppInfoParser.unregisterAppInfo(JMX_PREFIX, clientId, metrics);
```
### 拦截器
生产者拦截器需要实现 ```org.apache.kafka.clients.producer.ProducerInterceptor``` 接口，该接口定义了 4 个方法：
- ```onSend```：在将消息序列化和计算分区之前会调用，可以在该方法中修改消息
- ```onAcknowledgement``` ：在消息发送成功或失败之后调用，在 callback 之前调用；该方法会在 Producer 的发送 I/O 线程(ioThread)中运行，所以需要尽可能简单
- ```close```：用于关闭生产者拦截器时的一些清理工作
- ```configure```：用于设置拦截器需要的配置

通过实现 ```ProducerInterceptor``` 接口可以自定义生产者拦截器：
```java
public class CustomProducerInterceptor implements ProducerInterceptor<String, String> {

    private AtomicLong sendSuccess = new AtomicLong(0);
    private AtomicLong sendFailure = new AtomicLong(0);

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        String newValue = "prefix_" + record.value();
        return new ProducerRecord<>(
            record.topic(), 
            record.partition(), 
            record.timestamp(), 
            record.key(), 
            newValue, 
            record.headers());
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (exception == null){
            sendSuccess.incrementAndGet();
        }else{
            sendFailure.incrementAndGet();
        }
    }

    @Override
    public void close() {
        double successRatio = sendSuccess.doubleValue() / (sendSuccess.doubleValue() + sendFailure.doubleValue());
        System.out.println("发送成功率；" + successRatio);
    }

    @Override
    public void configure(Map<String, ?> configs) {

    }
}
```
在生产者配置中指定使用自定义的生产者拦截器即可生效：
```java
props.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, CustomProducerInterceptor.class.getName());
```
### 序列化器

```java
public class CustomSerializer implements Serializer<String> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {}

    @Override
    public byte[] serialize(String topic, String data) {
        return data.getBytes();
    }

    @Override
    public void close() {}
}
```
序列化器需要在创建生产者时指定：
```java
properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, CustomSerializer.class.getName());
properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, CustomSerializer.class.getName());
```
### 分区器

```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    // 主题的所有分区
	List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    int numPartitions = partitions.size();
    // key 为 null
    if (keyBytes == null) {
        // 维护一个整数，每次调用加 1
        int nextValue = nextValue(topic);
        // 主题的可用分区
        List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
        if (availablePartitions.size() > 0) {
            int part = Utils.toPositive(nextValue) % availablePartitions.size();
            return availablePartitions.get(part).partition();
        } else {
            // no partitions are available, give a non-available partition
            return Utils.toPositive(nextValue) % numPartitions;
        }
    } else {
        // hash the keyBytes to choose a partition
        return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
    }
}
```
通过实现 ```Partitioner``` 接口可以自定义分区器：
```java
public class CustomerPartitioner implements Partitioner{
    private final AtomicInteger counter = new AtomicInteger(0);

    @override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster){
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        int numberPartitions = partitions.size();
        if(null == keyBytes){
            return counter.getAndIncrement() % numberPartitions;
        }else{
            return Utils.toPositive(Utils.nurmur2(keyBytes) % numberPartitions);
        }
    }

    @override
    public void close(){}

    @override
    public void configure(Map<String, ?> configs){}
    
}
```
使用自定义的分区器需要在创建生产者实例之前配置：
```java
properties.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, CustomerPartitioner.class.getName());
```

### 消息发送流程

```java
@Override
public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
    // intercept the record, which can be potentially modified; this method does not throw exceptions
    ProducerRecord<K, V> interceptedRecord = this.interceptors.onSend(record);
    return doSend(interceptedRecord, callback);
}

public Future<RecordMetadata> doSend(ProducerRecord<K, V> record, Callback callback){
    // ...

    // 序列化器序列化消息
    serializedKey = keySerializer.serialize(record.topic(), record.headers(), record.key());
    serializedValue = valueSerializer.serialize(record.topic(), record.headers(), record.value());
    // 分区器对消息分区
    int partition = partition(record, serializedKey, serializedValue, cluster);
    // ...
	
    RecordAccumulator.RecordAppendResult result = accumulator.append(tp, timestamp, serializedKey, serializedValue, headers, interceptCallback, remainingWaitMs);
	// ...
}
```

```java
public final class RecordAccumulator{
    // ...
    private final int batchSize;
    private final CompressionType compression;
    private final ConcurrentMap<TopicPartition, Deque<ProducerBatch>> batches;

    // ...
}

```


```java
private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers,
									 Callback callback, Deque<ProducerBatch> deque) {
	ProducerBatch last = deque.peekLast();
	if (last != null) {
		FutureRecordMetadata future = last.tryAppend(timestamp, key, value, headers, callback, time.milliseconds());
		if (future == null)
			last.closeForRecordAppends();
		else
			return new RecordAppendResult(future, deque.size() > 1 || last.isFull(), false);
	}
	return null;
}
```




**[Back](../)**