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
### 日志文件
Kafka 消息是以日志的形式存储，在不考虑多副本的情况下，一个分区对应一个日志(Log)。为了便于消息的维护和清理，Kafka 引入了日志分段(LogSegment)将 Log 切分为多个 LogSegment。Log 和 LogSegment 是逻辑概念，Log 在物理上以文件夹的形式存储，而每个 LogSegment 对应于磁盘上的一个日志文件和两个索引文件，以及可能的其他文件。

Log 对应了一个命名形式为 ```<topic>-<partition>``` 的文件夹，向 Log 中追加消息时是顺序写入的，只有最后一个 LogSegment 才能执行写操作，在此之前的所有 LogSegment 都不能写入数据。当最后一个 LogSegment 满足一定条件时则变为只读，此时会创建一个新的 LogSegment 之后追加的消息需要写入新的 LogSegment。

每个 LogSegment 都有一个基准偏移量 baseOffset，用来表示当前 LogSegment 中第一条消息的 offset，基准偏移量是一个 64 位的长整型数，日志文件和索引文件都是根据基准偏移量命名的，名称固定为 20 位不足用 0 补，比如第一个 LogSegment 的基准偏移量为 0 则对应的日志文件为 00000000000000000000.log。

日志分段文件在一定条件会切分，相对应的索引文件也需要切分。日志分段文件切分包含以下一条即可触发切分：
- 当前日志分段文件的大小超过了 broker 端参数 ```log.segment.bytes``` 配置的值，默认是 1073741824(1GB)
- 当前日志分段中消息的最大时间戳与当前系统的时间戳的差值大于 ```log.roll.ms``` 或 ```log.roll.hours``` 参数配置的值(log.roll.ms 优先级大于 log.roll.hours)，默认情况下只配了 ```log.roll.hours``` 值为 168(7 天)
- 偏移量索引文件或时间戳索引文件的大小达到 broker 端参数 ```log.index.size.max.bytes``` 配置的值，默认是 10485760(10MB)
- 追加的消息的偏移量与当前日志分段的偏移量之间的差值大于 Integer.MAX_VALUE，即 ```offset - baseOffset > Integer.MAX_VALUE```

### 日志索引
每个日志分段(LogSegment)对应两个索引文件用来提高查询效率：
- 偏移量索引文件(.index 为后缀)用来建立消息偏移量(offset)到物理地址之间的映射关系方便快速定位消息所在的物理文件位置；
- 时间戳索引文件(.timeindex 为后缀)根据指定的时间戳(timestamp)来查找对应的的偏移量信息

Kafka 中的索引文件以稀疏索引的方式构造消息的索引，它并不保证每个消息在索引文件中都有对应的索引项。每当写入一定量(broker 端参数 ```log.index.interval.bytes``` 参数指定，默认 4096)的消息时，偏移量索引文件和时间戳索引文件分别增加一个偏移量索引项和时间戳索引项，增大或减少 ```log.index.interval.bytes``` 的值可以增加或缩小索引项的密度。从 LogSegment 的 append 方法中可以看到：
```java
// Update the in memory max timestamp and corresponding offset.
if (largestTimestamp > maxTimestampSoFar) {
  maxTimestampSoFar = largestTimestamp
  offsetOfMaxTimestamp = shallowOffsetOfMaxTimestamp
}
// append an entry to the index (if needed)
if (bytesSinceLastIndexEntry > indexIntervalBytes) {
  offsetIndex.append(largestOffset, physicalPosition)
  timeIndex.maybeAppend(maxTimestampSoFar, offsetOfMaxTimestamp)
  bytesSinceLastIndexEntry = 0
}
```
稀疏索引通过 MappedByteBuffer 将索引文件映射到内存中以加快索引的查询速度。偏移量索引文件中的偏移量是单调递增的，查找指定偏移量时使用二分查找法来快速定位偏移量的位置，如果指定的偏移量不在索引文件中则会返回小于该偏移量的最大偏移量。
```scala
def lookup(targetOffset: Long): OffsetPosition = {
  maybeLock(lock) {
    val idx = mmap.duplicate
    // 采用二分查找的方式
    val slot = largestLowerBoundSlotFor(idx, targetOffset, IndexSearchType.KEY)
    if(slot == -1)
	  OffsetPosition(baseOffset, 0)
    else
	  parseEntry(idx, slot).asInstanceOf[OffsetPosition]
  }
}
```
对于当前非活跃的日志分段而言，对应的索引文件内容已经固定不变所以会被设置为只读。而对当前活跃的日志分段而言对应的索引文件还会追加索引项所以被设置为可读写。在索引文件切分时 Kafka 会关闭当前正在写入的索引文件并设置为只读，同时创建可读写的新的索引文件，Kafka 在创建新的索引文件时会预分配固定大小的空间，由 broker 端的参数 ```log.index.size.max.bytes``` 配置。只有与当前活跃的日志分段对应的索引文件的大小固定为 ```log.index.size.max.bytes``` 而其余日志分段对应的索引文件的大小为实际的占用空间。

#### 偏移量索引
每个偏移量索引项占用 8 个字节，分为两部分：
- relativeOffset：相对偏移量，表示消息相对于 baseOffset 的偏移量，占用 4 个字节，当前索引文件的文件名即为 baseOffset 的值
- position：物理地址，也就是消息在日志分段文件中对应的物理位置，占用 4 个字节

消息的偏移量占用 8 个字节，也可以称为绝对偏移量。索引项中没有直接使用绝对偏移量而使用占用 4 字节的相对偏移量，可以减少索引文件占用的空间。

使用 ```kafka-dump-log.sh``` 脚本可以解析索引文件：
```shell
bin/kafka-dump-log.sh --file /kafka/log/path/log.index
```
在查找指定 offset 的消息时，首先会定位到相应的日志分段，然后根据日志分段的 baseOffset 计算出 relativeOffset 得到偏移量索引项，再根据 position 定位到具体的日志分段文件位置开始查找目标消息。

Kafka 的每个日志对象中使用了 ConcurrentSkipListMap 来保存各个日志分段，每个日志分段的 baseOffset 作为 key，这个可以根据指定偏移量来快速定位到消息所在的日志分段。 

Kafka 强制要求索引文件大小必须是索引项大小的整数倍，对偏移量索引文件而言，必须为 8 的整数倍。如果 broker 端的参数 ```log.index.size.max.bytes``` 配置不为 8 的整数倍会被自动调整为 8 的整数倍。
#### 时间戳索引
时间戳索引的每个索引项占用 12 个字节，分为两部分：
- timestamp：当前日志分段最大的时间戳，占用 8 字节
- relativeOffset：时间戳对应的消息的相对偏移量，占用 4 字节

时间戳索引文件中包含若干时间戳索引项，每个追加的时间戳索引项中的 timestamp 必须大于之前追加的索引项的 timestamp，如果 broker 端参数 ```log.message.timestamp.type``` 设置为 LogAppendTime，那么消息的时间戳必定能保证单调递增，如果是 CreateTime 类型则无法保证。

生产者可以指定时间戳的值，即使生产者客户端采用自动插入的时间戳也无法保证时间戳能够单调递增。

与偏移量索引文件相似，时间戳索引文件大小必须是索引项大小(12B)的整数倍，如果不满足也会进行裁剪。偏移量索引文件和时间戳索引文件的增加索引项操作是同时进行的，但是并不意味着偏移量索引项中的 relativeOffset 和时间戳索引项中的 relativeOffset 是同一个值

查找指定时间戳 targetTimeStamp 的消息首先是找到指定时间戳的日志分段，但是无法使用跳跃表来快速定位，需要以下步骤：
- 将 targetTimeStamp 和每个日志分段中的最大时间戳 largestTimeStamp 逐一对比，直到找到最大时间戳不小于 targetTimeStamp 的日志分段。日志分段中的 largestTimeStamp 的计算是先查找该日志分段所对应的时间戳索引文件找到最后一条索引项，如果最后一条索引项的时间戳字段值大于 0 则取其值否则取该日志分段的最近修改时间。
- 找到相应的日志分段之后在时间戳索引文件中使用二分查找算法找到不大于 targetTimeStamp 的最大索引项，因此找到了 relativeOffset
- 在偏移量索引文件中使用二分查找法查找到不大于 relativeOffset 的最大索引项得到 position
- 在找到的日志分段文件中的 position 位置开始查找不小于 targetTimeStamp 的消息

### 日志清理
Kafka 将消息存储在磁盘中，为了控制磁盘占用空间的不断增加需要对消息进行一定的清理操作。Kafka 提供两种日志清理策略：
- 日志删除(Log Retention)：按照一定的保留策略直接删除不符合条件的日志分段
- 日志压缩(Log Compaction)：针对每个消息的 key 进行整合，对于有相同 key 的不同 value 值只保留最后一个版本

通过 broker 端参数 ```log.cleanup.policy``` 设置日志清理策略，默认值为 delete，即采用日志删除的清理策略；如果要采用日志压缩的策略则需要设置为 compact，同时需要将 ```log.cleaner.enable``` 设置为 true；通过将 ```log.cleanpu.policy``` 设置为 ```delete,compact``` 可以同时支持日志删除和日志压缩两种策略。
#### 日志删除
Kafka 的日志管理器中会有一个专门的日志删除任务来周期性地检测和删除不符合保留条件的日志分段文件，这个周期由 broker 端参数 ```log.retention.check.interval.ms``` 来控制，默认 300000。当前日志分段的保留策略有三种：基于时间的保留策略、基于日志大小的保留策略和基于日志起始偏移量的保留策略。
##### 基于时间
日志删除任务会检查当前日志文件中是否有保留时间超过设定阈值(retentionMs)来寻找可删除的日志分段文件集合(deletableSegments)。retentionMs 可以通过 broker 端参数 ```log.retention..hours```、```log.retentiosn.minutes``` 和 ```log.retention.ms```来配置并且优先级依次递增，默认情况只配置了 ```log.retention.hours``` 为 168。

查找过期日志分段并不是简单的根据日志分段最近修改时间来计算，而是根据日志分段中消息的最大时间戳来计算。首先查询该日志分段对应的时间戳索引文件，查找时间戳索引文件中最后一条索引项，若最后一条索引项的时间戳字段大于 0 则取其值否则取最近修改时间。

若待删除的日志分段的总数等于该日志文件中所有的日志分段的数量，那么说明所有的日志分段已经过期，但该日志文件中还要有一个日志分段用于接收消息的写入，即必须要保证有一个活跃的日志分段，在这种情况下会先切分出一个新的日志分段作为获取日志分段然后执行删除操作。

删除日志分段时，首先会从 Log 对象中所维护的日志分段的跳跃表中移除待删除的日志分段，以保证没有线程对这些分段进行读取操作，然后将日志分段文所对应的所有文件(包含对应的索引文件)添加 ```.delete``` 后缀，最后由一个以 delete-file 命名的延迟任务删除这些文件，这个任务的延迟时间可以通过 ```file.delete.delay.ms``` 参数控制，默认为 60000。
##### 基于日志大小
日志删除任务会检查当前日志的大小是否超过设定的阈值(retentionSize)来寻找可删除的日志分段的文件集合(deletableSegments)。阈值大小可以通过 broker 端参数 ```log.retention.bytes``` 来配置，默认 -1 表示无穷大，该参数配置的所有日志分段文件的总大小而不是单个日志分段的大小，单个日志分段的大小由参数 ```log.segment.bytes``` 来控制，默认 173741824(1GB)。

基于日志大小保留策略首先计算日志文件总大小和 retentionSzie 的差值，然后从日志文件中的第一个日志分段开始查找可删除的日志分段集合，之后删除删除操作。
##### 基于日志的起始偏移量
基于日志起始偏移量的保留策略的判断依据是某日志分段的下一个日志分段的起始偏移量是否小于日志的起始偏移量 logStartOffset，如果是则可以删除此日志分段。
#### 日志压缩
日志压缩(Log Compaction)对于有相同 key 的不同 vlaue 值只会保留最新的 value 值，如果只关心最新的 value 值则可以开启日志合并功能，Kafka 会定期将相同 key 的消息进行合并只保留最新的 value 值。

Log Compaction 执行前后日志分段中的每条消息的偏移量和写入时的偏移量保持一致，Log Compaction 会生成新的日志分段文件，日志分段中每条消息的物理地址会重新按照新文件来组织。Log Compaction 执行之后的偏移量不再是连续的，但是并不影响日志的查询。

通过 log.dir 参数设置 Kafka 日志的存放目录，每一个日志目录下都有一个名为 cleanner-offset-checkpoint 的文件，这个文件就是清理检查点文件，用来记录每个主题的每个分区中已清理的偏移量。通过检查点文件可以将 Log 分为已经清理过的部分和未清理的部分。

活跃日志分段不会参与 Log Compaction，同时 Kafka 支持通过参数 ```log.cleaner.min.compaction.lag.ms``` 来配置消息在被清理前的最小保留时间。

Log Compaction 是针对 key 的，所以使用时每个消息的 key 不能为 null，每个 broker 会启动 log.cleaner.thread (默认为 1) 个日志清理线程负责执行清理任务，这些线程会选择 ```dirtyRatio = dirtyBytes /(cleanBytes +dirtyBytes)``` 最高的日志文件进行清理。

为了防止日志不必要的频繁清理操作，Kafka 使用参数 log.cleaner.min.cleanable.ratio (默认值为 0.5) 来限定可进行清理操作的最小 dirtyRatio。Kafka 中用于保存消费者消费位移的主题 __consumer_offsets 使用的就是 Log Compaction 策略。

Kafka 中的每个日志清理线程会使用一个名为 SkimpyOffsetMap 的对象来构建 key 与 offset 的映射关系的哈希表，日志清理需要遍历两次日志文件，第一次遍历把每个 key 的哈希值和最后出现的 offset 都保存在 SkimpyOffsetMap 中；第二次遍历会检查每个消息是否符合保留条件，如果符合就保留下来，否则就会被清理。

默认情况下，SkimpyOffsetMap 使用 MD5 来计算 key 的哈希值，占用空间大小为 16B，根据这个哈希值来从 SkimpyOffsetMap 中找到对应的槽位，如果发生冲突则用线性探测法处理。为了防止哈希冲突过于频繁，可以通过 ```log.cleaner.io.buffer.load.factor```(默认 0.9) 来调整负载因子。偏移量占用空间大小为 8B，因此一个映射项占用空间大小为 24B。

每个日志清理线程的 SkimpyOffsetMap 的内存占用大小为 log.cleaner.dedupe.buffer.size / log.cleaner.thread 默认是 128M/1=128M，所以默认情况下 SkimpyOffsetMap 可以保存 128M * 0.9/24B=5033164 个 key 的记录。

Log Compaction 会保留 key 相应的最新 value 值，当需要删除一个 key 时 Kafka 提供了墓碑消息(tombstone)的概念，如果一条消息的 key 不为 null，但是其 value 为 null，那么此消息就是墓碑消息。

日志清理线程发现墓碑消息时会先进行常规的清理并保留墓碑消息一段时间，墓碑消息的保留条件是当前墓碑消息所在的日志分段的最近修改时间 lastModifiedTime 大于 deleteHorizonMs，这个 deleteHorizonMs 的计算方式为 clean 部分中最后一个日志分段的最近修改时间减去保留阈值 deleteRetionMs（通过 log.cleaner.delete.retention.ms 配置，默认值 86400000）

Log Compaction 执行过后的日志分段的大小会比原先的日志分段文件小，为了防止出现太多的小文件，Kafka 在实际清理过程中并不对单个日志分段文件进行单独清理，而是将日志分段文件中 offset 从 0 到 firstUncleanableOffset 的所有日志分段进行分组，每个日志分段属于一组，分组策略为：按照日志分段的顺序遍历，每组中日志分段的占用空间大小之和不能超过 segmentSize (通过 log.segment.bytes 设置，默认 1GB)，且对应的索引文件占用大小之和不超过 maxIndexSize（通过 log.index.interval.bytes 设置，默认 10MB）。同一个组的多个日志分段清理过后只会生成一个新的日志分段。

Log Compaction 过程中会将每个日志分组中需要保留的消息复制到一个以 .clean 为后缀的临时文件中，此临时文件以当前日志分组中第一个日志分段的文件命名，如 00000000000000000000.log.clean。Log Compaction 之后将 .clean 的文件修改为 .swap 后缀的文件，然后删除原本的日志文件，最后把文件的 .swap 后缀去掉
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