## 消息发送

生产者客户端采用异步的方式发送消息，消息发送的流程是由两个线程协调完成，主线程负责将消息经拦截器、序列化器以及分区器等处理后缓存到 `RecordAccumulator`，Sender 线程则不断循环的从 `RecordAccumulator` 中取出消息发送到对应的 Broker。

```flow
producer=>start: KafkaProducer
accumulator=>subroutine: RecordAccumulator
sender=>subroutine: Sender
cluster=>end: Broker

producer(right)->accumulator(right)->sender(right)->cluster
```

## `RecordAccumulator`
主线程发送的消息缓存在 `RecordAccumulator` 以便可以批量的发送到 Broker，`RecordAccumulator` 定义了两个重要的属性用于缓存的管理：
```java
// 管理缓存内存空间
private final BufferPool free;
// 消息追加到双端队列中
private final ConcurrentMap<TopicPartition, Deque<ProducerBatch>> batches;
```
- `BufferPool` 管理 `RecordAccumulator` 缓存的内存空间，空间大小由客户端参数 `buffer.memory` 配置，默认 32M，当缓存空间不足时则会阻塞，阻塞时间由客户端参数 `max.block.ms` 控制，默认 60s。
- `batches` 为每个分区维护了一个双端队列，缓存的消息会直接追加到队尾的 `ProducerBatch` 中，如果 `ProducerBatch` 未创建或者已经满了则创建新的 `ProducerBatch` 并加入队尾。

主线程缓存消息的 `Accumulator` 时先根据消息的分区获取对应的双端队列，然后从双端队列的尾部获取 `ProducerBatch` 并尝试缓存：
```java
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
当 `ProducerBatch` 未创建或者满了时，`Accumulator` 需要创建新的 `ProducerBatch` 用于缓存消息，缓存消息的空间由 `BufferPool` 分配，大小取客户端参数 `batch.size` 和消息大小的最大值。当 `RecordAccumulator` 的缓存空间不足时，空间分配过程就会阻塞直到超时。
```java
int size = Math.max(this.batchSize, AbstractRecords.estimateSizeInBytesUpperBound(maxUsableMagic, compression, key, value, headers));
buffer = free.allocate(size, maxTimeToBlock);
```
`BufferPool` 分配的缓存空间为 `ByteBuffer`，为了避免频繁的创建和销毁 `ByteBuffer`，`BufferPool` 内部使用 `Deque<ByteBuffer>` 的结构缓存了大小为 `batch.size` 的 `ByteBuffer`，如果需要分配的缓存空间匹配则直接从队列头部取出，在使用完后加入到队列尾部，从而实现了内存的复用。

`ProducerBatch` 追加消息由 `MemoryRecordBuilder` 完成，当剩余空间不足则表示当前 `ProducerBatch` 满了，此时返回 null 给 `Accumulator` 以通知其创建新的 `ProducerBatch`。

Kafka 将消息合并为 `ProducerBatch` 发送，这样可以减少网络请求的次数从而提升整体的吞吐，但是如果客户端在 `RecordBatch` 未发送到 `Broker` 前发生故障可会导致消息丢失。

### `ProducerBatch`

### `BufferPool`


## Sender

主线程将消息缓存到 `RecordAccumulator` 后立即返回，Sender 线程负责将消息批量的发送到对应的 Broker。Sender 线程在 `KafkaProducer` 初始化的时候创建
```java
this.sender = newSender(logContext, kafkaClient, this.metadata);
String ioThreadName = NETWORK_THREAD_PREFIX + " | " + clientId;
this.ioThread = new KafkaThread(ioThreadName, this.sender, true);
this.ioThread.start();
```
Sender 线程从 RecordAccumulator 中获取缓存的 ProducerBatch 之后将 <TopicPartition, Deque<ProducerBatch>> 数据结构的消息转换为 <Node, List<ProducerBatch>> 数据结构的消息，其中 Node 表示 broker 节点。转换完成之后 Sender 还会进一步封装成 <Node, Request> 的形式，其中 Request 就是发送消息的请求 ProduceRequest，在发送消息之前 Sender 还会将请求保存到 ```Map<NodeId, Deque<Request>>``` 数据结构的 InFlightRequests 缓存已经发送请求但是没有收到响应的请求，通过 ```max.in.flight.requests.per.connection``` 控制与 broker 的每个连接最大允许未响应的请求数，默认是 5，如果较大则说明该 Node 负载较重或者网络连接有问题。

通过 InFlightRequest 可以得到 broker 中负载最小的，即 InFlightRequest 中未确认请求数最少的 broker，称为 leastLoadedNode

