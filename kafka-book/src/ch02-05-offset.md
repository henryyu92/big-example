# 消费位移
Kafka 分区的每个消息都有唯一的 offset 表示消息在分区中的位置，消费者在拉取到消息处理完成之后需要向 broker 提交分区下次 poll 的起始消息的 offset，即 poll 拉取的最后一条消息的 offset + 1。Kafka 将消费者提交的 offset 持久化到内部主题 ```__consumer_offsets``` 中，消费者在向 broker 拉取数据时，broker 在 ```__consumer_offsets``` 中获取拉取的起始消息位置。

对于 Kafka 分区而言每个消息都有唯一的 offet 表示消息在分区中的位置；对于消费者而言也有一个 offset 表示消费到的消息所在的位置。消费者每次调用 poll 方法是返回的是还没有被消费的消息集，因此 broker 需要记录上次消费到的 offset，Kafka 将消费的 offset 持久化在内部主题 __consumer_offsets 中。broker 在将数据发送给 Consumer 时先到 __consuemr_offsets 中查看需要发送的消息的起始位置。

消费者在消费完消息之后需要向 broker 提交下次发送数据的 offset，即当前消息集最后一个消息的 offset + 1。

消费者中有 position 和 committed offset 的概念，分别表示下一次拉取的消息的起始位移和已经提交过的消费位移。KafkaConsumer 提供了 ```position(TopicPartition)``` 和 ```committed(TopicPartition)``` 两个方法获取 position 和 committed offset。
```java
ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
Set<TopicPartition> partitions = records.partitions();
for (TopicPartition partition : partitions){
  consumer.position(partition);
  consumer.committed(partition);
  // 同步提交
  consumer.commitSync();
  // 下一次拉取的 offset
  consumer.position(partition);
  // 已经提交的 offset
  consumer.committed(partition);
}
```
Kafka 默认的消费位移提交方式是自动提交，即 ```enable.auto.commit``` 配置默认为 true，默认提交是每隔一个周期自动提交，这个周期是由 ```auto.commit.interval.ms``` 配置，默认时间间隔为 5s。自动提交的动作是在 poll 方法中完成的，默认方式下消费者每隔 5s 拉取到每个分区中最大的消费位移，在每次真正向服务器端发起拉取请求之前会检查是否可以进行位移提交，如果可以则提交上次轮询的位移。

Kafka 提供了手动提交位移，这样可以使得对消费位移的管理控制更加灵活，使用手动位移提交需要关闭自动提交即 ```enable.auto.commit``` 设置为 false，然后使用 ```KafkaConsumer#commitSync()``` 同步提交或者使用 ```KafkaConsumer#commitAsync()``` 异步提交。
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
#### 指定位移消费

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


#### `__consumer_offset`
位移提交的内容最终会保存到 Kafka 的内部主题 __consumer_offsets 中。一般情况下，当集群中第一次有消费者消费消息时会自动创建主题 __consumer_offsets，副本因子可以通过 ```offsets.topic.replication.factor``` 参数设置，分区数可以通过 ```offsets.topic.num.partitions``` 参数设置

客户端提交消费位移是使用 OffsetConmmitRequest 请求实现的，OffsetCommitRequest 的结构如下：
- group_id
- generation_id
- member_id
- retention_time 表示当前提交的消费位移所能保留的时长，通过 ```offsets.retention.minutes``` 设置
- topics

最终提交的消费位移会以消息的形式发送到主题 __consumer_offsets，与消费位移对应的消息也只定义了 key 和 value 字段的具体内容，它不依赖于具体版本的消息格式，以此做到与具体的消息格式无关。

在处理完消费位移之后，Kafka 返回 OffsetCommitResponse 给客户端，OffsetCommitResponse 的结构如下：
```java
```
可以通过 ```kafka-console-consumer.sh``` 脚本来查看 __consumer_offsets 中的内容：
```shell
```
如果有若个案消费者消费了某个主题的消息，并且也提交了相应的消费位移，那么在删除这个主题之后会一并将这些消费位移信息删除。

#### 消费位移管理
```kafka-consumer-groups.sh``` 脚本提供了通过 reset-offsets 指令来重置消费组内的消费位移，前提是该消费组内没有消费者运行：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--group groupIdMonitor --all-topics --reset-offsets --to-earliest
```