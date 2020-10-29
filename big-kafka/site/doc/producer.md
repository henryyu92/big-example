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
生产者需要序列化器将消息对象转换为字节数组才能通过网络传给 broker，消费者需要将接收到的字节数组反序列化为消息对象。Kafka 客户端自带了多种序列化器，它们都实现了 ```org.apache.kafka.common.serialization.Serializer``` 接口：
- ```serialize``` 方法用于执行序列化操作，将数据序列化为字节数组
- ```close``` 方法用于关闭当前的序列化器，如果实现需要保证幂等性
- ```configure``` 方法设置序列化器的配置

生产者使用的序列化器和消费者使用的反序列化器需要对应，否则会出现解析错误。通过实现 ```Serializer``` 接口可以自定义序列化器：
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
消息在通过 send 方法发往 broker 的过程中需要经过拦截器、序列化器和分区器的作用。消息经过序列化后需要分区器确定发往的分区，如果消息(ProducerRecord)指定了 partition 则直接发往该分区否则分区器根据消息的 key 计算发往的分区。

Kafka 中分区器需要实现 ```org.apache.kafka.clients.producer.Partitioner``` 接口，接口定义了两个方法：
- ```partition``` 方法用来计算分区号
- ```close``` 方法用于关闭分区器

Kafka 提供了默认分区器 ```DefaultPartitioner```，partition 方法中的分区策略是：
- 如果 key 不为 null 则对 key 进行哈希(采用 MurmurHash2 算法)，然后根据哈希值对 topic 所有的分区取模获取分区号
- 如果 key 为 null，则查找 topic 的所有可用分区，然后通过轮询的方式将消息发往这些分区

在不改变主题分区数量的情况下，key 与分区之间的映射可以保持不变，一旦主题增加了分区，那么就难以保证 key 与分区之间的映射关系了。
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

生产者客户端由两个线程协调运行，分别为主线程和 Sender 线程。在主线程中 KafkaProducer 创建的消息经过拦截器、序列化器和分区器作用之后缓存到消息累加器(RecordAccumulator)中，Sender 线程负责从 RecordAccumulator 中获取消息并将其发送到 broker 中。
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
RecordAccumulator 主要用于缓存消息以便 Sender 线程可以批量发送消息从而减少网络传输的资源消耗以提升性能，缓存的大小可以通过 ```buffer.memory``` 参数控制，默认是 33554432B 即 32M；如果生产者发送消息的速度过快则 send 方法会阻塞超时后抛出异常，阻塞时间由 ```max.block.ms``` 参数控制，默认是 60000 即 60s。

RecordAccumulator 通过 ```ConcurrentMap<TopicPartition, Deque<ProducerBatch>> ``` 为每个分区维护一个存储 ```ProducerBatch``` 的双端队列，主线程中发送的消息将会被追加到 RecordAccumulator 的与 TopicPartition 对应分区的双端队列 ```Deque<ProducerBatch>``` 中，消息写入 Deque 的尾部，Sender 线程从 Deque 的头部消费。
```java
public final class RecordAccumulator{
    // ...
    private final int batchSize;
    private final CompressionType compression;
    private final ConcurrentMap<TopicPartition, Deque<ProducerBatch>> batches;

    // ...
}

```

客户端的消息是以字节的方式传输到 broker，Kafka 客户端中是以 ByteBuffer 实现消息在内存的创建和释放，RecordAccumulator 内部实现了一个 BufferPool 用于重复利用 ByteBuffer 从而减少重复的创建和销毁 ByteBuffer。BufferPool 只针对特定大小的 ByteBuffer 进行管理，而其他大小的 ByteBuffer 不会缓存仅 BufferPool 中，这个特定大小由 ```batch.size``` 参数决定，默认值为 16384B，即 16K

ProducerBatch 包含一个或多个 ProducerRecord 这样使得网络请求减少提升吞吐量，当消息缓存到 Accumulator 时首先查找消息分区对应的双端队列(如果没有则创建)，再从这个双端队列的尾部获取一个 ProducerBatch(如果没有则创建)，如果可以写入则写入否则创建一个新的 ProducerBatch，在新建 ProducerBatch 时如果消息大小不超过 ```batch.size``` 则创建大小为 batch.size 的 ProducerBatch，否则按照消息的实际大小创建。为了避免频繁的创建和销毁 ProducerBatch，RecordAccumulator 内部维护一个 BufferPool，当 ProducerBatch 的大小不超过 ```batch.size``` 则这块内存将交由 BuffPool 来管理进行复用。
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
Sender 线程从 RecordAccumulator 中获取缓存的 ProducerBatch 之后将 <TopicPartition, Deque<ProducerBatch>> 数据结构的消息转换为 <Node, List<ProducerBatch>> 数据结构的消息，其中 Node 表示 broker 节点。转换完成之后 Sender 还会进一步封装成 <Node, Request> 的形式，其中 Request 就是发送消息的请求 ProduceRequest，在发送消息之前 Sender 还会将请求保存到 ```Map<NodeId, Deque<Request>>``` 数据结构的 InFlightRequests 缓存已经发送请求但是没有收到响应的请求，通过 ```max.in.flight.requests.per.connection``` 控制与 broker 的每个连接最大允许未响应的请求数，默认是 5，如果较大则说明该 Node 负载较重或者网络连接有问题。

通过 InFlightRequest 可以得到 broker 中负载最小的，即 InFlightRequest 中未确认请求数最少的 broker，称为 leastLoadedNode

### 元数据更新
元数据是指 kafka 集群的元数据，这些元数据记录了集群中的主题、主题对应的分区、分区的 leader 和 follower 分配的节点等信息，当客户端没有需要使用的元数据信息时或者超过 ```metadata.max.age.ms```(默认 300000s 即 5 分钟) 没有更新元数据信息时会触发元数据的更新操作。

生产者启动时由于 bootstrap.server 没有配置所有的 broker 节点，因此需要触发元数据更新操作，当分区数量发生变化或者分区 leader 副本发生变化时也会触发元数据更新操作。

客户端的元数据更新是在内部完成的，对外不可见。客户端需要更新元数据时，首先根据 InFlightRequests 获取负载最低的节点 leastLoadedNode(未确认请求最少的节点)，然后向这个节点发送 MetadataRequest 请求来获得元数据信息，更新操作是由 Sender 线程发起，在创建完 MetadataRequest 之后同样会存入 InFlightRequests。


### 生产者重要参数
- ```acks```：指定分区中必须有多少个副本收到消息之后才会认为消息写入成功，acks 有三个可选值：
  - ```acks=0```：生产者发送消息后不需要等待任务服务端的响应。如果在消息发送到 broker 的过程中出现网络异常或者 broker 发生异常没有接受消息则会导致消息丢失，但这种配置能够达到最大吞吐量
  - ```acks=1```：默认次设置，生产者发送消息后需要分区 leader 副本响应消息写入成功。如果 leader 写入成功并且在 follower 同步消息之前退出，则从 ISR 中新选举出的 leader 没有这条消息，也会造成消息丢失，这种配置时消息可靠和吞吐量的折中
  - ```acks=all```：生产者发送消息后需要分区的 ISR 中所有 follower 副本写入成功之后才能收到写入成功的响应，能够保证可靠性，但是吞吐量会受到很大影响
- ```max.request.size```：限制客户端能发送的消息的最大值，默认 1M
- ```retries 和 retry.backoff.ms```：retries 参数用于配置生产者重试的次数，默认是 0。retry.backoff.ms 设置重试之间的时间间隔，默认是 100
- ```compression.type```：指定消息的压缩方式，默认为 "none"，压缩消息可以减少网络传输量从而降低网络 IO，但是压缩/解压缩操作会消耗额外的 CPU 资源
- ```connections.max.idle.ms```：连接空闲时间，默认 540000ms，超过空闲时间的连接会关闭
- ```linger.ms```：指定生产者发送 ProducerBatch 之前等待的时间，默认为 0。生产者客户端在 ProducerBatch 填满或者等待时间超过 linger.ms 指定时间就发送，增大此参数会增加消息延时但能提升一定的的吞吐量
- ```receive.buffer.bytes```：设置 Socket 接受消息缓冲区(SO_RECBUG)的大小，默认为 32768B(32KB)，如果设置为 -1 则使用操作系统的默认值
- ```send.buffer.bytes```：设置 Socket 发送消息缓冲区(SO_SENDBUF)的大小，默认为 131072B(128KB)，如果设置为 -1 则使用操作系统的默认值
- ```request.timeout.ms```：配置 Producer 等待请求响应的最长时间，默认值为 30000ms

生产者详细参数：

|参数名|默认值|含义|
|-|-|:-:|
|bootstrap.server|""|指定连接 kafka 集群所需的 broker 地址列表|
|key.serializer||消息中 key 的序列化类|
|value.serializer||消息中 value 的序列化类|
|buffer.memory|33554432(23M)|生产者客户端中用于缓存消息的缓冲区大小|
|batch.size|16384(16K)|指定 ProducerBatch 可以复用的内存区域大小|
|client.id|""|设置 KafkaProducer 的 clientId|
|max.block.ms|60000|KafkaProducer 中 send 和 partitionsFor 方法阻塞时长|
|partitioner.class|DefaultPartitioner|指定生产者分区器|
|enable.idempotence|false|是否开启幂等功能|
|interceptor.class|""|指定生产者拦截器|
|max.in.flight.request.per.connection|5|限制每个连接最多缓存的请求数|
|metadata.max.age.ms|300000(5min)|元数据更新间隔，超过此间隔则强制更新|
|transactional.id|null|指定事务 id，必须唯一|

**[Back](../)**