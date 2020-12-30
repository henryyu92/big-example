## 生产者

Kafka 中生产者是负责发送消息的客户端，消息在生产者客户端经过分区以及序列化后发送到 `Broker` 集群。生产者客户端维护了整个 `Broker` 集群的元数据，在发送数据前会根据元数据信息确定消息所属的分区对应的 `Broker`。

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
```java
producer.send(record, (metadata, exception) -> {
    
    if (exception != null) {
        
        // handle exception
    } else {
        // handle result
    }
});
```

### 拦截器

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

序列化器负责将消息序列化成二进制形式，Kafka 提供了 `Serializer` 接口定义序列化器，通过实现接口可以自定义消息的序列化方式：
```java
byte[] serialize(String topic, T data);
```
Kafka 提供了常见的序列化方器，包括 `StringSerializer`, `ByteArraySerialzier` 等，通过实现 `Serializer` 接口也可以自定义序列化算法。Kafka 消息的 key 和 value 都需要序列化，序列化器需要在创建生产者客户端时指定：
```java
properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "key_serializer_class_name");
properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "value_serializer_class_name");
```

### 分区器

消息在发送到 `Broker` 之前需要确定消息所在的分区，客户端在创建消息 `ProducerRecord` 时如果指定了 `partition` 则消息会被发送到 `partition` 对应的 `Broker`，否则需要根据消息的 `key` 进行计算消息所在的分区。

Kafka 提供 `Partitioner` 接口用于定义分区器，通过实现接口可以定义消息对应分区的算法，Kafka 内置了三种分区器实现。

#### `DefaultPartitioner`

`DefaultPartitioner` 是 Kafka 默认的分区器，在消息的 key 为空时使用 `StickyPartitioner` 来计算分区，在消息的 key 不为空时则直接将 key 哈希后对消息所属的主题的分区数取模得到当前消息的分区。
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    if (keyBytes == null) {
        return stickyPartitionCache.partition(topic, cluster);
    } 
    List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    int numPartitions = partitions.size();
    // hash the keyBytes to choose a partition
    return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
}
```
在消息所属的主题的分区数不发生变化的情况下，`DefaultPartition` 保证具有相同的 key 的消息计算到同一个分区，但是主题的分区数如果发生变化，则就无法保证这种对应关系。

#### `RoundRobinPartitioner`
`RoundRobinPartitioner` 为消息对应的 `topic` 维护了一个计数器，如果消息所属主题的**可用分区**集合不为空则通过将计数器对可用分区数取模得到消息的分区；否则将计数器对**所有分区**取模得到消息的分区。
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
    int numPartitions = partitions.size();
    int nextValue = nextValue(topic);
    List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
    if (!availablePartitions.isEmpty()) {
        int part = Utils.toPositive(nextValue) % availablePartitions.size();
        return availablePartitions.get(part).partition();
    } else {
        // no partitions are available, give a non-available partition
        return Utils.toPositive(nextValue) % numPartitions;
    }
}
```

//todo 可用分区 和 所有分区

#### `UniformStickyPartitioner`
`UniformStickyPartitioner` 缓存了主题上个消息的分区，
```java
public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
    return stickyPartitionCache.partition(topic, cluster);
}
```
粘滞分区器

通过实现 `Partitioner` 接口可以自定义分区算法，分区器需要在创建生产者实例时配置：
```java
properties.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, "partitioner_class_name");
```

### 核心流程

生产者客户端最主要的功能是向 `Broker` 集群发送消息，消息通过拦截器、分区器和序列化器的作用确定了消息的信息，之后消息就可以通过网络发送到 `Broker`。

消息发送前还需要确定存储消息所在分区的 `Broker`，Kafka 生产者客户端维护着整个 `Broker` 集群的元数据信息，并且在集群发生变化时动态的更新这些元数据。

#### 消息发送

生产者的消息是异步发送的，消息发送的流程是由两个线程协调完成。主线程在消息经过拦截器、分区器和序列化器的作用并缓存到 `RecordAccumulator` 后返回，`Sender` 线程则不断循环从 `RecordAccumulator` 中获取消息然后发送到对应的 `Broker`。

```flow
producer=>start: KafkaProducer
accumulator=>subroutine: RecordAccumulator
sender=>subroutine: Sender
cluster=>end: Broker

producer(right)->accumulator(right)->sender(right)->cluster
```
消息并不是直接追加到 `RecordAccumulator`，而是先追加到 `ProducerBatch` 然后再添加到 `RecorAccumulator` 为每个分区维护的双端队列中，如果 `ProducerBatch` 满了则会关闭当前的 Batch 然后创建新的 Batch。`RecordAccumulator` 缓存的大小由参数 `buffer.memory` 设置(默认 32MB)，如果生产者生产消息的速度比发送到 `Broker` 的速度快则在 `RecordAccumulator` 空间不足时阻塞 `max.block.ms`，默认 60000

```java
synchronized (dq) {
    // 追加消息到 Accumulator 返回 null 则表示需要创建新的 ProducerBatch
    RecordAppendResult appendResult = tryAppend(timestamp, key, value, headers, callback, dq);
    if (appendResult != null) {
        // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
        return appendResult;
    }
    // 创建新的 ProducerBatch
    MemoryRecordsBuilder recordsBuilder = recordsBuilder(buffer, maxUsableMagic);
    ProducerBatch batch = new ProducerBatch(tp, recordsBuilder, time.milliseconds());
    FutureRecordMetadata future = Objects.requireNonNull(batch.tryAppend(timestamp, key, value, headers,
            callback, time.milliseconds()));

    dq.addLast(batch);
    // 还未发送的 Batch
    incomplete.add(batch);
}

// 获取队列尾部的 ProducerBatch 并追加，如果 Batch 已经满了则关闭 batch 以释放资源
private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers,
                                     Callback callback, Deque<ProducerBatch> deque) {
    ProducerBatch last = deque.peekLast();
    if (last != null) {
        FutureRecordMetadata future = last.tryAppend(timestamp, key, value, headers, callback, time.milliseconds());
        if (future == null)
            last.closeForRecordAppends();
        else
            return new RecordAppendResult(future, deque.size() > 1 || last.isFull(), false, false);
    }
    return null;
}
```
创建新的 `ProducerBatch` 时会传入内存池对象 `BufferPool` 用于存储消息，`BufferPool` 的大小由参数 `batch.size` 和消息大小之间的最大值确定。
```java
int size = Math.max(this.batchSize, AbstractRecords.estimateSizeInBytesUpperBound(maxUsableMagic, compression, key, value, headers));
buffer = free.allocate(size, maxTimeToBlock);
```
`ProducerBatch` 在追加消息时会追加到构建的 `MemoryRecordsBuilder` 中，当生于空间不足则表示当前 `ProducerBatch` 满了，此时返回 null 给 `Accumulator` 以通知其创建新的 `ProducerBatch`。
```java
public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Header[] headers, Callback callback, long now) {
    if (!recordsBuilder.hasRoomFor(timestamp, key, value, headers)) {
        return null;
    } else {
        Long checksum = this.recordsBuilder.append(timestamp, key, value, headers);
        this.maxRecordSize = Math.max(this.maxRecordSize, AbstractRecords.estimateSizeInBytesUpperBound(magic(),
                recordsBuilder.compressionType(), key, value, headers));
        this.lastAppendTime = now;
        FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                               timestamp, checksum,
                                                               key == null ? -1 : key.length,
                                                               value == null ? -1 : value.length,
                                                               Time.SYSTEM);
        // we have to keep every future returned to the users in case the batch needs to be
        // split to several new batches and resent.
        thunks.add(new Thunk(callback, future));
        this.recordCount++;
        return future;
    }
}
```
主线程将消息追加到 `RecordAccumulator` 后立即返回，缓存的消息则由 Sender 线程发送到对应的 `Broker`。




Kafka 将消息合并为 `ProducerBatch` 发送，这样可以减少网络请求的次数从而提升整体的吞吐，但是如果客户端在 `RecordBatch` 未发送到 `Broker` 前发生故障可会导致消息丢失。


```java

```


// todo 内存池


#### 元素据更新




### 生产者管理




### 参数调优

- ```acks```：指定分区中必须有多少个副本收到消息之后才会认为消息写入成功，acks 有三个可选值：
  - ```acks=0```：生产者发送消息后不需要等待任务服务端的响应。如果在消息发送到 broker 的过程中出现网络异常或者 broker 发生异常没有接受消息则会导致消息丢失，但这种配置能够达到最大吞吐量
  - ```acks=1```：默认次设置，生产者发送消息后需要分区 leader 副本响应消息写入成功。如果 leader 写入成功并且在 follower 同步消息之前退出，则从 ISR 中新选举出的 leader 没有这条消息，也会造成消息丢失，这种配置时消息可靠和吞吐量的折中
  - ```acks=all```：生产者发送消息后需要分区的 ISR 中所有 follower 副本写入成功之后才能收到写入成功的响应，能够保证可靠性，但是吞吐量会受到很大影响
- ```max.request.size```：限制客户端能发送的消息的最大值，默认 1M
- `retries `：配置生产者重试的次数，默认是 0
- `retry.backoff.ms`：设置重试之间的时间间隔，默认是 100
- ```compression.type```：指定消息的压缩方式，默认为 "none"，压缩消息可以减少网络传输量从而降低网络 IO，但是压缩/解压缩操作会消耗额外的 CPU 资源
- ```connections.max.idle.ms```：连接空闲时间，默认 540000ms，超过空闲时间的连接会关闭
- ```linger.ms```：指定生产者发送 ProducerBatch 之前等待的时间，默认为 0。生产者客户端在 ProducerBatch 填满或者等待时间超过 linger.ms 指定时间就发送，增大此参数会增加消息延时但能提升一定的的吞吐量
- ```receive.buffer.bytes```：设置 Socket 接受消息缓冲区(SO_RECBUG)的大小，默认为 32768B(32KB)，如果设置为 -1 则使用操作系统的默认值
- ```send.buffer.bytes```：设置 Socket 发送消息缓冲区(SO_SENDBUF)的大小，默认为 131072B(128KB)，如果设置为 -1 则使用操作系统的默认值
- ```request.timeout.ms```：配置 Producer 等待请求响应的最长时间，默认值为 30000ms
- `buffer.memory`  33554432(23M)  生产者客户端中用于缓存消息的缓冲区大小  
- `batch.size`  16384(16K)  指定 ProducerBatch 可以复用的内存区域大小  
- `client.id`  ""  设置 KafkaProducer 的 clientId  
- `max.block.ms`  60000  KafkaProducer 中 send 和 partitionsFor 方法阻塞时长  
- `partitioner.class`  DefaultPartitioner  指定生产者分区器  
- `enable.idempotence`  false  是否开启幂等功能  
- `interceptor.class`  ""  指定生产者拦截器  
- `max.in.flight.request.per.connection`  5  限制每个连接最多缓存的请求数  
- `metadata.max.age.ms`  300000(5min)  元数据更新间隔，超过此间隔则强制更新  
- `transactional.id`  null  指定事务 id，必须唯


**[Back](../)**