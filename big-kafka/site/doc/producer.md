## 生产者

Kafka 中生产者是负责发送消息的客户端，消息在生产者客户端经过分区以及序列化后发送到 `Broker` 集群，生产者客户端不需要寻址消息存储的 `Broker` 从而使得整个客户端不会有过重的负载影响业务性能。

`KafkaProducer` 对象表示生产者客户端，在创建实例的时候需要指定必要的参数：
- `bootstrap.servers`：broker 地址列表，具体格式为 `host1:prot1,host2:port2`，这里不需要配置所有 broker 的地址因为生产者会从给定的 broker 里查找到其他 broker 的信息
- `key.serializer`：消息的 `key` 的序列化类，消息的 `key` 用于计算消息所属的分区
- `value.serializer`：消息的 `value` 的序列化类，`value` 是实际需要发送的消息，客户端在将消息发送到 broker 之前需要对消息进行序列化处理

`KafkaProducer` 是线程安全的，因此可以以单例的形式创建，也可以将 `KafkaProducer` 实例进行池化以在高并发的情况下提升系统的吞吐:
```java
// todo KafkaProducerFactory
```

### 消息发送

Kafka 客户端将要发送的消息包装为 `ProducerRecord` 对象用于后续的处理，其包含了多个消息相关的属性：
```java
public class ProducerRecord<K, V> {
    // 消息的主题
    private final String topic;
    // 消息的分区
    private final Integer partition;
    // 消息头
    private final Headers headers;
    // 消息的 key
    private final K key;
    // 消息内容
    private final V value;
    // 消息创建时间或者追加到日志的时间
    private final Long timestamp;
	
    // ...
}
```
`ProducerRecord` 定义了生产者向 `Broker` 发送的消息格式，其中 `topic` 在消息创建时必须指定。`partition` 如果没有指定则消息所在的分区会通过 `key` 来计算。`timestamp` 字段在不同的配置下有不同的涵义，如果消息所属的主题在 `Broker` 中设置为 `CREATE_TIME` 则表示生产者创建消息的时间戳，如果设置为 `LOG_APPEND_TIME` 则表示 `Broker` 将消息追加到日志的时间戳。

Kafka 生产者客户端以异步的方式发送消息，返回的 `Future` 对象包含了消息发送的结果，Kafka 提供了两种消息发送的重载:
```java
public Future<RecordMetadata> send(ProducerRecord<K, V> record);

public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback);
```
如果指定了 `callback`，Kafka 生产者客户端会在消息发送完成后调用。消息发送完成后返回的结果由 `RecordMetadata` 表示，其中包含了消息的分区、消息偏移量等信息：
```java
public final class RecordMetadata {
    // 消息偏移量
    private final long offset;
    // 消息创建时间或者追加到日志的时间
    private final long timestamp;
    // key 序列化后的字节数
    private final int serializedKeySize;
    // value 序列化后的字节数
    private final ing serializedValueSize;
    // 消息的 Topic 和 Partition 信息
    private final TopicPartition topicPartition;
    // 消息的 CRC32 校验和
    private volatile Long checksum;
	
    // ...
}
```
生产者客户端在发送消息的过程中会产生两类异常：可重试异常和不可重试异常。对于可重试异常，如果在创建生产者客户端时配置 `retries(默认 0)` 参数则在发生异常时会自动重试，对于不可重试异常则会直接抛出:
```
producer.send(record, (metadata, exception) -> {
    
    if (exception != null){
        
        // handle exception
    }
});
```

### 生产者拦截器

生产者拦截器在消息发送前以及消息发送完成后作用，可用于对发送的消息以及返回的结果作统一的处理。Kafka 提供了 `ProducerInterceptor` 接口定义生产者拦截器，该接口定义了三个方法：
```java
// 在 send 方法之后，消息系列化和分区之前调用
// 可以在这个方法中修改消息，消息修改后会导致后续的操作作用于新的消息
// 方法中抛出的异常会被 catch 而不会继续向上层抛出
public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record);

// 收到消息确认或者消息发送失败之后调用
// 在 callback 之前调用，方法抛出的异常会被忽略
// 方法运行在 I/O 线程，因此方法的实现需要尽可能简单
public void onAcknowledgement(RecordMetadata metadata, Exception exception);

// 关闭拦截器时调用
public void close();
```
`ProducerInterceptor` 接口同时继承了 `Configurable` 接口，可以通过 `configure` 方法获取客户端配置的参数。生产者拦截器需要在创建生产者客户端实例时设置，在创建实例的配置中添加配置：
```java
properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "interceptor.class.name");
```

### 序列化器

序列化器负责将消息序列化成二进制形式，

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

### 生产者流程

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