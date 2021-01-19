# 位移提交
Kafka 中每个消息都有唯一的 offset 表示消息在分区的位置，消费者通过 poll 方法拉取消息后需要向集群提交 offset 用于持久化，以便在发生再均衡时消费者能够从正常的 offset 开始消费。

`KafkaConsumer` 提供了 `committed(partition)` 方法和 `position(partiton)` 方法分别用来获取已经提交的 offset 和下一次拉取消息的起始 offset，通过返回的分区的 offset 信息，可以手动的控制消费者客户端消息的拉取以及 offset 的提交。
```java
// 获取指定分区已经提交的 offset 信息
public OffsetAndMetadata committed(TopicPartition partition)

// 获取下一次拉取消息的起始 offset
public long position(TopicPartition partition)
```
`KafkaConsumer` 默认是自动提交 offset，即参数 `enable.auto.commit` 设置为 true，默认情况下消费者客户端每隔固定周期计算当前每个分区已经拉取的最大消息 offset 并在下次拉取消息时提交，间隔时间由参数 `auto.commit.interval.ms` 配置，默认 5s。

Kafka 提供了手动提交位移，使用手动位移提交需要关闭自动提交即 `enable.auto.commit=false`，然后使用 `KafkaConsumer#commitSync()` 同步提交或者使用 `KafkaConsumer#commitAsync()` 异步提交。

## 同步提交
同步提交方式会阻塞线程直到 offset 提交完成，
```java
final int batchSize = 200;
List<ConsumerRecord> buffer = new ArrayList<>();
while(isRunning){
  ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
  for(ConsumerRecord<String, String> record : records){
    buffer.add(record);
  }
  if(buffer.size() >= batchSize){
    consumer.commitSync();
    buffer.clear();
  }
}
```
同步提交会根据 poll 方法拉取的最新位移进行提交，只要没有发生不可恢复的错误就会一直阻塞线程直到提交完成。使用无参的 ```commitSync()``` 方法同步提交时提交频率和消息处理的频率一致并且也存在消息重复的问题，如果需要细粒度控制提交则可以使用 ```commitSync(Map<TopicPartition, OffsetAndMetadata>)``` 方法：
```java
while(isRunning){
  ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
  for(ConsumerRecord<String, String> record : records){
    // 消息的 offset
    long offset = record.offset();
    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
    // 提交分区的 offset
    consumer.commitSync(Collections.singletonMap(partition, new OffsetAndMetadata(offset + 1)))
  }
}
```
同步提交消费位移时阻塞式的，因此可以按照分区的粒度来进行提交：
```java
while (isRunning){
  // 拉取消息
  ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
  // 按照分区来分组消息集合
  for(TopicPartition partition : records.partitions()){
    List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
    for (ConsumerRecord<String, String> record : partitionRecords){
      // do some logical processing.
    }
    // 分区最后一条消息的 offset
    long lastConsumedOffset = partitionRecords.get(partitionRecords.size() - 1).offset();
    consumer.commitSync(Collections.singletonMap(partition, new OffsetAndMetadata(lastConsumedOffset + 1)));
  }
}
```
## 异步提交
异步提交(commitAsync)的方式在提交时不会阻塞消费者线程，可能在提交消费位移结果返回之前开始了新一次的拉取操作。KafkaConsumer 提供了三个重载方法用于异步提交：
```java
public void commitAsync()

public void commitAsync(OffsetCommitCallback callback)

public void commitAsync(final Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback)
```
回调函数 callback 用于位移提交后处理异步提交的结果，一般用于异步提交异常时的重试策略。在消费者异常退出的情况下异步提交需要保证消费位移的提交，一般使用同步提交来提交异常之后的消费位移：
```java
try{
  while(isRunning){
    //...
    consumser.commitAsync(new OffsetCommitCallback(){
      @Override
      void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception){
        if(exception != null){
          // 重新提交消费位移策略
        }
        System.out.println(offsets);
      } 
    });
  }
}finally{
  try{
    // 防止有未提交的消费位移
    consumer.commitSync();
  }finally{
    consumer.close();
  }
}
```
## 指定位移

Kafka 中当消费者查找不到所记录的消费位移或者位移越界时，就会根据消费者客户端参数 ```auto.offset.reset``` 的配置决定消费消息的起始位置，默认是 "latest" 表示从分区末尾开始消费，如果设置为 "earliest" 则表示从头(也就是 0)开始消费，如果设置为 "none" 则表示在获取不到消费位移时抛出 NoOffsetForPartitionException 异常。

KafkaConsumer 提供了 seek 方法用于精确控制从特定的位置开始消费，partition 表示消费的分区，offset 表示从分区指定位置开始消费：
```java
public void seek(TopicPartition partition, long offset)
```
seek 方法只能重置消费者分配到的分区的消费位置，而分区的分配是在 poll 方法中完成的，因此在执行 seek 方法之前需要调用 poll 方法获得分配的分区之后才能重置消费位置：
```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList(topic));
Set<TopicPartition> assignment = new HashSet<>();
// 如果不为 0 则表示分区分配完成
while(assignment.size == 0){
    consumer.poll(Duration.ofMillis(1000));
    // 获取消费者分区信息
    topicPartitions = consumer.assignment();
}
for(TopicPartition tp : topicPartitions){
  // 指定分区消费位置
  consumer.seek(tp, 10);
}
while(isRunning){
  // 再次从 seek 设定的位移处开始拉取消息
  ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
}
```
如果对未分配到的分区执行 seek 方法则会抛出 IllegalStateException 异常。如果消费组内的消费者能够找到消费位移，除非发生位移越界否则 ```auto.offset.reset``` 配置并不会起效，此时需要通过 seek 指定从头部或者从尾部消费：
```java
KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList(topic));
Set<TopicPartition> assignment = new HashSet<>();
while(assignment.size() == 0){
  consumer.poll(Duration.ofMillis(1000));
  partitions = consumer.assignment();
}
// 获取分区中最后一个消息的位移
Map<TopicPartition, Long> offsets = consumer.endOffsets(partitions);
for(TopicPartition tp : partitions){
  consumer.seek(tp, offsets.get(tp));
}
```
endOffsets 方法用于获取指定分区的末尾的消息位置，相对应的 beginningOffsets 方法用于获取指定分区的初始消息位置。KafkaConsumer 还提供了 offsetsForTimes 方法通过 timestamp 来查找分区对应的位置：
```java
Map<TopicPartition, Long> timestampToSearch = new HashMap<>();
for(TopicPartition tp : partitions){
  timestampToSearch.put(tp, System.currentTimeMillis() - 24 * 3600 * 1000);
}
// 返回分区中时间戳大于等于待查询的时间的第一条消息的 offset
Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(timestampToSearch);
for(TopicPartition tp : partitions){
  OffsetAndTimestamp offsetAndTimestamp = offsets.get(tp);
  if(offsetAndTimestamp != null){
    consumer.seek(tp, offsetAndTimestamp.offset());
  }
}
```
使用 seek 方法可以不使用内部主题 ```__consumer_offsets``` 中而可以存储在任意介质中，在拉取消息之读取位移然后使用 seek 设置分区位移可以实现消费位移的完全控制。




