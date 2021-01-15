# 消息

消息是 Kafka 中流通的数据，生产者客户端将业务生成的消息包装成 `ProducerRecord` 发送到集群，Broker 将接收到的消息转换成 `Record` 并以 Log 的形式持久化在磁盘，消费者客户端从集群拉取消息并包装成 `ConsumerRecord` 给下游业务使用。
```java
public class DefaultRecord implements Record {
  
  // 消息大小
  private final int sizeInBytes;
  private final byte attributes;
  // 消息的 offset
  private final long offset;
  // 消息的时间戳(创建时间/追加时间)
  private final long timestamp;
  private final int sequence;
  // 消息的 key
  private final ByteBuffer key;
  // 消息的 value
  private final ByteBuffer value;
  private final Header[] headers;
  
  // ...
}
```
Kafka 将消息以批次(`RecordBatch`)的方式追加到日志，每个 `RecordBatch` 包含了多个
```
RecordBatch =>
 BaseOffset => Int64                        RecordBatch 的起始 offset                
 Length => Int32
 PartitionLeaderEpoch => Int32              分区 leader 的版本号或更新次数
 Magic => Int8                              消息格式版本号
 CRC => Uint32
 Attributes => Int16
 LastOffsetDelta => Int32                   
 FirstTimestamp => Int64
 MaxTimestamp => Int64
 ProducerId => Int64
 ProducerEpoch => Int16
 BaseSequence => Int32
 Records => [Record]
```

Kafka 消息(Record)是总是以分批(RecordBatch)的形式写入，一个 RecordBatch 包含一个或多个 Record。

RecordBatch 的格式如下：
- baseOffset：占用 8 个字节，表示当前 RecordBatch 的起始位移
- length：占用 4 个字节，计算从 partitionLeaderEpoch 开始到末尾的长度
- partitionLeaderEpoch：占用 4 个字节，表示分区 leader 的 epoch，即分区 leader 的版本号或更新次数
- magic：消息格式版本号，占用 1 字节
- crc：从 atrributes 往后所有数据的检验和，占用 4 个字节
- attributes：消息的属性，占用 2 个字节，低 3 位表示压缩类型：0-NONE，1-GZIP，2-SNAPPY，3-LZ4；第 4 位表示 timestamp 类型：0-CreateTime，1-LogAppendTime；第 5 位表示事务消息：0 表示非事务消息；第 6 位表示是否是 control batch：0 表示不是；第 6 到 15 位没有使用
- lastOffsetDelta：占用 4 个字节，保存 RecordBatch 中最后一个 Record 的 offset 与 baseOffset 的差值，用于确保 RecordBatch 中 Record 组装的正确性
- firstTimestamp：占用 8 个字节，RecordBatch 中第一条 Record 的时间戳
- maxTimestamp：占用 8 个字节，RecordBatch 中最大的时间戳，一般情况下是最后一个 Record 的时间戳
- producerId：占用 8 个字节
- producerEpoch：占用 2 个字节，用于支持幂等和事务
- baseSequence：占用 4 个字节，用于支持幂等和事务
- records：Record 数据

如果 attributes 字段是控制批次(Control Batch)，则 RecordBatch 只包含一个称为 ControlRecord 的记录，ControlRecord 不会传到应用而是用于消费者过滤被 abort 的事务消息。ControlRecord 的格式如下：
- version：占用 2 个字节，默认是 0
- type：占用 2 个字节，0 表示 abort，1 表示 commit

Kafka 消息(Record) 中包含了 Headers：
- length：消息总长度，变长 int 
- atrributes：占用 1 个字节，未使用
- timestampDelta：时间戳增量，表示与 RecordBatch 的起始时间戳的差值
- offsetDelta：位移增量，保存与 RecordBatch 起始位移的差值
- keyLength：表示消息的长度，如果为 -1 表示没有设置 key，占用 4 个字节
- key：可选，没有 key 就没有这个字段
- valueLength：实际消息体的长度，如果为 -1 表示消息为空，占用 4 个字节
- value：消息体，可以为空
- headers：Header

Header 的格式如下：
- headerKeyLength：变长 int
- headerKey：String
- headerValueLength：变长 int
- value

使用 ```kafka-dump-log.sh``` 脚本可以查看日志的格式：
```shell
bin/kafka-dump-log.sh --files /kafka/log/file
```