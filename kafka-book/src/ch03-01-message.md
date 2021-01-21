# 消息

消息是 Kafka 中流通的数据，生产者客户端将业务生成的消息包装成 `ProducerRecord` 发送到集群，Broker 将接收到的消息转换成 `Record` 并以 Log 的形式持久化在磁盘，消费者客户端从集群拉取消息并包装成 `ConsumerRecord` 给下游业务使用。

## 消息格式


消息以`RecordBatch`形式追加到日志，每个 `RecordBatch` 包含了多个 `Record`，此外还包含了每个 `RecordBatch` 的信息。
```
RecordBatch =>
  BaseOffset => Int64                        RecordBatch 的起始 offset                
  Length => Int32                            计算从 partitionLeaderEpoch 开始到末尾的长度
  PartitionLeaderEpoch => Int32              分区 leader 的版本号或更新次数
  Magic => Int8                              消息格式版本号
  CRC => Uint32                              从 Attributes 开始的所有数据的校验和
  Attributes => Int16                        消息的属性
    bit 0-2             压缩类型
        0               NONE
        1               GZIP
        2               SNAPPY
        3               LZ4
        4               ZSTD
    bit 3               timestamp 类型：0-CreateTime，1-LogAppendTime
    bit 4               事务消息：0 表示非事务消息
    bit 5               是否是 control batch：0 表示不是
    bit 6-15            未使用
  LastOffsetDelta => Int32                   RecordBatch 中最后一个 Record 的 offset 与 baseOffset 的差值
  FirstTimestamp => Int64                    RecordBatch 中第一条 Record 的时间戳
  MaxTimestamp => Int64                      RecordBatch 中最大的时间戳，一般情况下是最后一个 Record 的时间戳
  ProducerId => Int64
  ProducerEpoch => Int16                     用于支持幂等和事务
  BaseSequence => Int32                      用于支持幂等和事务
  Records => [Record]
```


如果 attributes 字段是控制批次(Control Batch)，则 RecordBatch 只包含一个称为 ControlRecord 的记录，ControlRecord 不会传到应用而是用于消费者过滤被 abort 的事务消息。ControlRecord 的格式如下：
- version：占用 2 个字节，默认是 0
- type：占用 2 个字节，0 表示 abort，1 表示 commit
```
ControlBatch =>
  version: int16 (current version is 0)
  type: int16 (0 indicates an abort marker, 1 indicates a commit)
```

```
Record =>
  Length => Varint                          消息的长度
  Attributes => Int8                        消息属性，暂时未使用
  TimestampDelta => Varlong                 与 RecordBatch 的起始时间戳的差值
  OffsetDelta => Varint                     与 RecordBatch 起始位移的差值
  KeyLength => Varint                       Key 的长度，-1 表示没有 key
  Key => Bytes                              消息的 key
  ValueLength => Varint                     Value 的长度，-1 表示没有 value
  Value => Bytes                            消息的值
  Headers => [HeaderKey HeaderValue]
    HeaderKey => String
    HeaderValue => Bytes

Header =>
  headerKeyLength: varint
  headerKey: String
  headerValueLength: varint
  Value: byte[]
```


## 消息转换

ProducerRecord

ProducerBatch

MemoryRecords

消息在内存中的形式，

DefaultRecord

DefaultRecordBatch

ConsumerRecords

ConsumerRecord

使用 ```kafka-dump-log.sh``` 脚本可以查看日志的格式：
```shell
bin/kafka-dump-log.sh --files /kafka/log/file
```