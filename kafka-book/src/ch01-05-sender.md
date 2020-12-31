# 消息发送
生产者的消息是异步发送的，消息发送的流程是由两个线程协调完成。主线程在消息经过拦截器、分区器和序列化器的作用并缓存到 `RecordAccumulator` 后返回，`Sender` 线程则不断循环从 `RecordAccumulator` 中获取消息然后发送到对应的 `Broker`。

```flow
producer=>start: KafkaProducer
accumulator=>subroutine: RecordAccumulator
sender=>subroutine: Sender
cluster=>end: Broker

producer(right)->accumulator(right)->sender(right)->cluster
```

## Accumulator

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
### BufferPool

// todo 内存池

## Sender
