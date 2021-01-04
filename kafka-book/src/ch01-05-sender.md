## 消息发送

生产者客户端采用异步的方式发送消息，消息发送的流程是由两个线程协调完成，主线程负责将消息经拦截器、序列化器以及分区器等处理后缓存到 `RecordAccumulator`，Sender 线程则不断循环的从 `RecordAccumulator` 中取出消息发送到对应的 Broker。

```flow
producer=>start: KafkaProducer
accumulator=>subroutine: RecordAccumulator
sender=>subroutine: Sender
cluster=>end: Broker

producer(right)->accumulator(right)->sender(right)->cluster
```

### `RecordAccumulator`

`RecordAccumulator` 主要用来缓存消息以便消息可以批量的发送到 Broker，从而提升消息发送的效率。`RecordAccumulator` 为每个分区维护了一个双端队列，缓存的消息首先根据分区得到缓存该分区的双端队列，然后再缓存到双端队列中。
```java
private final ConcurrentMap<TopicPartition, Deque<ProducerBatch>> batches;
```
`RecordAccumulator` 维护的双端队列中缓存的并不是 `ProducerRecord`，而是 `ProducerBatch`，消息追加到 `RecordAccumulator` 时会从消息分区对应的双端队列尾部获取 `ProducerBatch` 并追加，如果 Batch 还未创建或者已满则需要创建新的 Batch。
```java
synchronized (dq) {
    // 追加消息到 ProducerBatch 返回 null 则表示需要创建新的 ProducerBatch
    RecordAppendResult appendResult = tryAppend(timestamp, key, value, headers, callback, dq);
    if (appendResult != null) {
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
```
Kafka 使用 `BufferPool` 来管理用于缓存消息的内存空间，缓存空间大小由客户端参数 `buffer.memory` 配置，默认 32M。`ProducerBatch` 在创建时由 `BufferPool` 负责分配内存，大小由参数 `batch.size` 和消息大小之间的最大值确定，在内存不足时分配过程会阻塞，阻塞时间由客户端参数 `max.block.ms` 控制，默认 60s。
```java
int size = Math.max(this.batchSize, AbstractRecords.estimateSizeInBytesUpperBound(maxUsableMagic, compression, key, value, headers));
buffer = free.allocate(size, maxTimeToBlock);
```
`BufferPool` 在分配内存空间时，如果分配的内存大小为 `batch.size` 则复用 ByteBuffer 而不会频繁的创建和删除，如果消息大于 `batch.size` 则不会复用，在发送的消息比较大的情况可以适当的增大 `batch.size` 从而避免频繁创建和销毁 ByteBuffer 造成的 GC。

`ProducerBatch` 追加消息实际由 `MemoryRecordBuilder` 完成，当生于空间不足则表示当前 `ProducerBatch` 满了，此时返回 null 给 `Accumulator` 以通知其创建新的 `ProducerBatch`。
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

Kafka 将消息合并为 `ProducerBatch` 发送，这样可以减少网络请求的次数从而提升整体的吞吐，但是如果客户端在 `RecordBatch` 未发送到 `Broker` 前发生故障可会导致消息丢失。


### Sender

主线程将消息缓存到 `RecordAccumulator` 后立即返回，消息发送到 Broker 就需要 Sender 线程完成。Sender 线程在 `KafkaProducer` 初始化的时候创建
```java
this.sender = newSender(logContext, kafkaClient, this.metadata);
String ioThreadName = NETWORK_THREAD_PREFIX + " | " + clientId;
this.ioThread = new KafkaThread(ioThreadName, this.sender, true);
this.ioThread.start();
```

Sender 线程从 RecordAccumulator 中获取缓存的 ProducerBatch 之后将 <TopicPartition, Deque<ProducerBatch>> 数据结构的消息转换为 <Node, List<ProducerBatch>> 数据结构的消息，其中 Node 表示 broker 节点。转换完成之后 Sender 还会进一步封装成 <Node, Request> 的形式，其中 Request 就是发送消息的请求 ProduceRequest，在发送消息之前 Sender 还会将请求保存到 ```Map<NodeId, Deque<Request>>``` 数据结构的 InFlightRequests 缓存已经发送请求但是没有收到响应的请求，通过 ```max.in.flight.requests.per.connection``` 控制与 broker 的每个连接最大允许未响应的请求数，默认是 5，如果较大则说明该 Node 负载较重或者网络连接有问题。

通过 InFlightRequest 可以得到 broker 中负载最小的，即 InFlightRequest 中未确认请求数最少的 broker，称为 leastLoadedNode

