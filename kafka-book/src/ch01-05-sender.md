## 消息发送
生产者的消息是异步发送的，消息发送的流程是由两个线程协调完成。主线程在消息经过拦截器、分区器和序列化器的作用并缓存到 `RecordAccumulator` 后返回，`Sender` 线程则不断循环从 `RecordAccumulator` 中获取消息然后发送到对应的 `Broker`。

```flow
producer=>start: KafkaProducer
accumulator=>subroutine: RecordAccumulator
sender=>subroutine: Sender
cluster=>end: Broker

producer(right)->accumulator(right)->sender(right)->cluster
```

### Accumulator

`Accumulator` 主要用来缓存消息

消息并不是直接追加到 `RecordAccumulator`，而是先追加到 `ProducerBatch` 然后再添加到 `RecorAccumulator` 为每个分区维护的双端队列中，如果 `ProducerBatch` 满了则会关闭当前的 Batch 然后创建新的 Batch。`RecordAccumulator` 缓存的大小由参数 `buffer.memory` 设置(默认 32MB)，如果生产者生产消息的速度比发送到 `Broker` 的速度快则在 `RecordAccumulator` 空间不足时阻塞 `max.block.ms`，默认 60000

```java
synchronized (dq) {
    // 追加消息到 Accumulator 返回 null 则表示需要创建新的 ProducerBatch
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


`KafkaProducer` 在创建的时候会初始化 `Sender` 线程，



生产者客户端由两个线程协调运行，分别为主线程和 Sender 线程。在主线程中 KafkaProducer 创建的消息经过拦截器、序列化器和分区器作用之后缓存到消息累加器(RecordAccumulator)中，Sender 线程负责从 RecordAccumulator 中获取消息并将其发送到 broker 中。

RecordAccumulator 主要用于缓存消息以便 Sender 线程可以批量发送消息从而减少网络传输的资源消耗以提升性能，缓存的大小可以通过 ```buffer.memory``` 参数控制，默认是 33554432B 即 32M；如果生产者发送消息的速度过快则 send 方法会阻塞超时后抛出异常，阻塞时间由 ```max.block.ms``` 参数控制，默认是 60000 即 60s。

RecordAccumulator 通过 ```ConcurrentMap<TopicPartition, Deque<ProducerBatch>> ``` 为每个分区维护一个存储 ```ProducerBatch``` 的双端队列，主线程中发送的消息将会被追加到 RecordAccumulator 的与 TopicPartition 对应分区的双端队列 ```Deque<ProducerBatch>``` 中，消息写入 Deque 的尾部，Sender 线程从 Deque 的头部消费。



客户端的消息是以字节的方式传输到 broker，Kafka 客户端中是以 ByteBuffer 实现消息在内存的创建和释放，RecordAccumulator 内部实现了一个 BufferPool 用于重复利用 ByteBuffer 从而减少重复的创建和销毁 ByteBuffer。BufferPool 只针对特定大小的 ByteBuffer 进行管理，而其他大小的 ByteBuffer 不会缓存仅 BufferPool 中，这个特定大小由 ```batch.size``` 参数决定，默认值为 16384B，即 16K

ProducerBatch 包含一个或多个 ProducerRecord 这样使得网络请求减少提升吞吐量，当消息缓存到 Accumulator 时首先查找消息分区对应的双端队列(如果没有则创建)，再从这个双端队列的尾部获取一个 ProducerBatch(如果没有则创建)，如果可以写入则写入否则创建一个新的 ProducerBatch，在新建 ProducerBatch 时如果消息大小不超过 ```batch.size``` 则创建大小为 batch.size 的 ProducerBatch，否则按照消息的实际大小创建。为了避免频繁的创建和销毁 ProducerBatch，RecordAccumulator 内部维护一个 BufferPool，当 ProducerBatch 的大小不超过 ```batch.size``` 则这块内存将交由 BuffPool 来管理进行复用。



Sender 线程从 RecordAccumulator 中获取缓存的 ProducerBatch 之后将 <TopicPartition, Deque<ProducerBatch>> 数据结构的消息转换为 <Node, List<ProducerBatch>> 数据结构的消息，其中 Node 表示 broker 节点。转换完成之后 Sender 还会进一步封装成 <Node, Request> 的形式，其中 Request 就是发送消息的请求 ProduceRequest，在发送消息之前 Sender 还会将请求保存到 ```Map<NodeId, Deque<Request>>``` 数据结构的 InFlightRequests 缓存已经发送请求但是没有收到响应的请求，通过 ```max.in.flight.requests.per.connection``` 控制与 broker 的每个连接最大允许未响应的请求数，默认是 5，如果较大则说明该 Node 负载较重或者网络连接有问题。

通过 InFlightRequest 可以得到 broker 中负载最小的，即 InFlightRequest 中未确认请求数最少的 broker，称为 leastLoadedNode

