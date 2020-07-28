### 消息格式
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
bin/kafka-dump-log.sh --file /kafka/log/file
```





### 磁盘存储
Kafka 依赖文件系统来存储消息，采用文件追加的方式来写入消息，即只能在日志文件的尾部追加新的消息，并且不允许修改已写入的消息。
#### 页缓存
页缓存是操作系统实现的一种主要的磁盘缓存，以此用来减少对磁盘 I/O 的操作。当一个进程准备读取磁盘上的文件内容时，操作系统会先查看待读取的数据所在的页(page)是否在页缓存(pagecache)中，如果存在则直接返回；如果没有则操作系统会向磁盘发起读取请求并将读取的数据缓存入页缓存，之后再将数据返回给进程。

如果一个进程需要将数据写入磁盘，操作系统也会检测数据对应的页是否在页缓存中，如果不存在则先会在页缓存中添加相应的页，最后将数据写入对应的页，被修改的页变成了脏页操作系统会在合适的时候把脏页中的数据写入磁盘，以保证数据的一致性。

Kafka 中大量使用了页缓存，虽然消息都是先被写入页缓存然后由操作系统负责具体的刷盘任务，但 Kafka 也提供了同步刷盘及间断性强制刷盘的功能，这些功能可以通过 ```log.flush.interval.message``` 和 ```log.flush.interval.ms``` 来控制。

Linux 系统会使用磁盘的一部分作为 swap 分区，这样可以进行进程的调度：把当前非活跃的进程调入 swap 分区，以此把内存空出来让给活跃的进程。对于大量使用页缓存的 Kafka 而言，应当避免这种内存的交换，否则会对性能产生较大的影响。可以通过修改 ```vm.swappiness``` 参数(Linux 系统参数)来进行调节，```vm.swappiness``` 参数的上限为 100 表示积极地使用 swap 分区并把内存上的数据及时的搬运到 swap 分区；下限为 0 表示任何情况都不要发生交换，这样当内存耗尽时会终止某些进程。

### 日志同步机制
在分布式系统中，日志同步机制要保证数据一致性也要保证数据的顺序性。日志同步机制的一个基本原则是：如果客户端已经成功提交了某条消息，那么即使 leader 退出，也要保证新选出来的 leader 中能够包含这条消息。

Kafka 动态维护者一个 ISR 集合，处于 ISR 集合内的节点保持与 leader 相同的 HW，只有在 ISR 中的副本才有资格被选为新的 leader，位于 ISR 中的任何副本节点都有资格成为 leader。


[Back](../)