## 日志文件

Kafka 的消息在 broker 上以追加日志的形式存储，每个分区的每个副本在 broker 上都有各自的日志文件。日志文件位于 ```log.dirs``` 配置的目录下的 ```<topic>-<partitionId>``` 目录下。

Kafka 每个日志文件都有两个索引文件以提高查询效率：
- 以 ```.index``` 为后缀的偏移量索引文件，记录日志文件中消息 offset 与物理存储辞职的映射
- 以 ```.timeindex``` 为后缀的时间戳索引文件，记录日志文件中消息 timestamp 与消息 offset 的映射


broker 上的日志文件以及索引文件都是以基准偏移量(base offset)命名的，基准偏移量是一个 64 位的长整型数，表示当前日志文件中的第一条消息的 offset。


### 日志追加

Log 是 kafka 日志的抽象，生产者向 broker 发送的消息是以 Log 的形式存储到文件中。Log 是一个 LogSegment 序列，每个 LogSegment 都有一个基准偏移量(base offset) 表示当前 LogSegment 中第一条消息的 offset。





### 日志切分

日志分段文件在一定条件会切分，相对应的索引文件也需要切分。日志分段文件切分包含以下一条即可触发切分：
- 当前日志分段文件的大小超过了 broker 端参数 ```log.segment.bytes``` 配置的值，默认是 1073741824(1GB)
- 当前日志分段中消息的最大时间戳与当前系统的时间戳的差值大于 ```log.roll.ms``` 或 ```log.roll.hours``` 参数配置的值(log.roll.ms 优先级大于 log.roll.hours)，默认情况下只配了 ```log.roll.hours``` 值为 168(7 天)
- 偏移量索引文件或时间戳索引文件的大小达到 broker 端参数 ```log.index.size.max.bytes``` 配置的值，默认是 10485760(10MB)
- 追加的消息的偏移量与当前日志分段的偏移量之间的差值大于 Integer.MAX_VALUE，即 ```offset - baseOffset > Integer.MAX_VALUE```


## 日志索引

Kafka 中的索引文件以稀疏索引的方式构造消息的索引，它并不保证每个消息在索引文件中都有对应的索引项。当写入 ```log.index.interval.bytes``` 参数(默认 4096)指定的消息时，偏移量索引文件增加一条消息集中最大的 offset 及其物理地址的索引项，时间戳索引文件增加一条消息集中最大的 timestamp 及其 offset 的索引项。
```java
// LogSegment#append

// Update the in memory max timestamp and corresponding offset.
if (largestTimestamp > maxTimestampSoFar) {
  maxTimestampSoFar = largestTimestamp
  // 消息集中 timestamp 最大的 offset
  offsetOfMaxTimestamp = shallowOffsetOfMaxTimestamp
}
// append an entry to the index (if needed)
if (bytesSinceLastIndexEntry > indexIntervalBytes) {
  // 偏移量索引文件增加索引项
  offsetIndex.append(largestOffset, physicalPosition)
  // 时间戳索引文件增加索引项
  timeIndex.maybeAppend(maxTimestampSoFar, offsetOfMaxTimestamp)
  bytesSinceLastIndexEntry = 0
}
```

稀疏索引通过 ```MappedByteBuffer``` 将索引文件映射到内存中以加快索引的查询速度。偏移量索引文件中的偏移量是单调递增的，查找指定偏移量时使用二分查找法来快速定位偏移量的位置，如果指定的偏移量不在索引文件中则会返回小于该偏移量的最大偏移量。
```java
// OffsetIndex#lookup

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
当日志切分的时候日志索引也会切分，Kafka 会关闭当前正在写入的索引文件并设置为只读，同时 Kafka 会创建一个新的可读写的索引文件。创建新的索引文件时会预先分配固定的大小，其大小由参数 ```log.index.size.max.bytes``` 设置，当索引文件关闭时文件大小为实际大小。

### 偏移量索引

偏移量索引 (OffsetIndex) 中记录了消息 offset 与其物理地址的对应关系，每个索引项 (IndexEntry) 占用 8 个字节，包含两部分内容：
- ```relativeOffset```：表示消息相对于 baseOffset 的偏移量，占用 4 个字节
- ```position```：消息在日志分段文件中对应的物理位置，占用 4 个字节

```java
// OffsetIndex#append

def append(offset: Long, position: Int): Unit = {
  inLock(lock) {
    require(!isFull, "Attempt to append to a full index (size = " + _entries + ").")
    if (_entries == 0 || offset > _lastOffset) {
      trace(s"Adding index entry $offset => $position to ${file.getAbsolutePath}")
      // 写入 relativeOffset
      mmap.putInt(relativeOffset(offset))
      // 写入 position
      mmap.putInt(position)
      _entries += 1
      _lastOffset = offset
      require(_entries * entrySize == mmap.position(), entries + " entries but file position in index is " + mmap.position() + ".")
    } else {
      throw new InvalidOffsetException(s"Attempt to append an offset ($offset) to position $entries no larger than" +
        s" the last offset appended (${_lastOffset}) to ${file.getAbsolutePath}.")
    }
  }
}
```
Kafka 中消息的 offset 是 8 个字节，偏移量索引中存储的是消息的 offset 和 baseOffset 的差值，只占用了 4 个字节，减少了索引文件占用的空间，因此一个偏移量索引文件中最多只能记录 ```Int.MaxValue``` 的记录：
```java
// OffsetIndex#relativeOffset

private def toRelative(offset: Long): Option[Int] = {
  val relativeOffset = offset - baseOffset
  if (relativeOffset < 0 || relativeOffset > Int.MaxValue)
    None
  else
    Some(relativeOffset.toInt)
}
```

使用 ```kafka-dump-log.sh``` 脚本可以解析索引文件：
```shell


```

broker 在根据 offset 查找指定 offset 的消息时，首先根据 offset 找到包含消息的 LogSegment，然后通过偏移量索引找到消息对应的物理地址，之后根据物理地址在 LogSegment 中遍历找到。
```java
// Log#read

// 查找 offset 消息对应的 LogSegment
var segmentEntry = segments.floorEntry(startOffset)

// LogSegment#read

// 获取偏移量索引中消息对象的 offset 和 position
val startOffsetAndSize = translateOffset(startOffset)

```

Kafka 使用 ```ConcurrentSkipListMap``` (跳跃表) 来保存 LogSegment，其中 key 是 LogSegment 的 baseOffset。```floorEntry``` 方法返回跳跃表中不大于查找消息的 offset 的最大 baseOffset 的 LogSegment，这样就能确定查找的消息所在的 LogSegment。

Kafka 强制要求索引文件大小必须是索引项大小的整数倍，对偏移量索引文件而言，必须为 8 的整数倍。如果 broker 端的参数 ```log.index.size.max.bytes``` 配置不为 8 的整数倍会被自动调整为 8 的整数倍。

### 时间戳索引

时间戳索引 (TimeIndex) 中记录了消息的 timestamp 和消息的 offset 的对应关系，时间戳索引中的每个索引项占用 12 个字节，包含两部分内容：
- timestamp：当前日志分段最大的时间戳，占用 8 字节
- relativeOffset：时间戳对应的消息的相对偏移量，占用 4 字节

```java
// TimeIndex#maybeAppend

if (timestamp > lastEntry.timestamp) {
  trace(s"Adding index entry $timestamp => $offset to ${file.getAbsolutePath}.")
  // 写入 timestamp
  mmap.putLong(timestamp)
  // 写入 relativeOffset
  mmap.putInt(relativeOffset(offset))
  _entries += 1
  _lastEntry = TimestampOffset(timestamp, offset)
  require(_entries * entrySize == mmap.position(), _entries + " entries but file position in index is " + mmap.position() + ".")
}
```
时间戳索引文件追加的索引项中的 timestamp 必须大于之前追加的索引项的 timestamp，如果参数 ```log.message.timestamp.type``` 设置为 ```LogAppendTime``` 则可以保证 timestamp 单调递增，如果设置为 ```CreateTime```，除非生产者在生产消息时能够指定单调递增的时间戳，否则时间戳索引无法保证会被写入。


与偏移量索引文件相似，时间戳索引文件大小必须是索引项大小(12B)的整数倍，如果不满足也会进行裁剪。

偏移量索引 (OffsetIndex) 和时间戳索引 (TimeIndex) 增加索引项操作是同时进行的，但是偏移量索引项中的 relativeOffset 和时间戳索引项中的 relativeOffset 不一定是同一个值，因为写入偏移量索引的 offset 是消息集中最大的 offset，而写入时间戳索引的 offset 是消息集中 timestamp 最大的消息的 offset。

broker 根据 timestamp 查找消息时首先需要根据 timestamp 定位到消息所在的 LogSegment，然后在 LogSegment 中先根据 timestamp 在 TimeIndex 时间戳索引找到 timestamp 对应的 offset，然后在 OffsetIndex 偏移量索引中根据 offset 找到消息所在的 postion，最后在 LogSegment 中从 position 开始遍历查找指定 timestamp 的消息。

根据 timestamp 定位消息所在的 LogSegment 时是遍历每个 LogSegment，将 LogSegment 中的 largestTimestamp 与查找的 timestamp 比较，找到所有 largestTimestamp 小于查找的 timestamp 的 LogSegment。LogSegment 中的 largestTimestamp 在每次写入时间戳索引时更新为时间戳索引中的 timestamp。
```java

/**
* The largest timestamp this segment contains.
*/
def largestTimestamp = if (maxTimestampSoFar >= 0) maxTimestampSoFar else lastModified

// Log#fetchOffsetByTimestamp

val targetSeg = {
  // Get all the segments whose largest timestamp is smaller than target timestamp
  val earlierSegs = segmentsCopy.takeWhile(_.largestTimestamp < targetTimestamp)
  // We need to search the first segment whose largest timestamp is greater than the target timestamp if there is one.
  if (earlierSegs.length < segmentsCopy.length)
    Some(segmentsCopy(earlierSegs.length))
  else
    None
}

targetSeg.flatMap(_.findOffsetByTimestamp(targetTimestamp, logStartOffset))
```
确定到 LogSegment 之后遍历这些 LogSegment 并通过二分查找算法找到不大于指定的 timestamp 的最大索引项，然后根据找到的索引项中的 relativeOffset 在偏移量索引中通过二分查找找到不大于 relativeOffset 的最大索引项，最后根据 postion，在 LogSegment 中从 position 位置开始查找指定的 timestamp 的消息。 
```java
def findOffsetByTimestamp(timestamp: Long, startingOffset: Long = baseOffset): Option       [TimestampAndOffset] = {
  // Get the index entry with a timestamp less than or equal to the target timestamp
  val timestampOffset = timeIndex.lookup(timestamp)
  val position = offsetIndex.lookup(math.max(timestampOffset.offset, startingOffset)).position

  // Search the timestamp
  Option(log.searchForTimestamp(timestamp, position, startingOffset))
}
```

## 日志清理

Kafka 将消息存储在磁盘中，为了控制磁盘占用空间的不断增加需要对消息进行一定的清理操作。Kafka 提供两种日志清理策略：
- ```Log Retention```：按照一定的保留策略直接删除不符合条件的日志分段
- ```Log Compaction```：针对每个消息的 key 进行整合，对于有相同 key 的不同 value 值只保留最后一个版本

通过 broker 端参数 ```log.cleanup.policy``` 设置日志清理策略，默认值为 delete，即采用日志删除的清理策略；如果要采用日志压缩的策略则需要设置为 compact，同时需要将 ```log.cleaner.enable``` 设置为 true；通过将 ```log.cleanpu.policy``` 设置为 ```delete,compact``` 可以同时支持日志删除和日志压缩两种策略。

Kafka 在启动时会启动 LogManager 用于日志文件的管理，LogManager 启动时创建 ```kafka-log-retention``` 线程用于周期性的 (```log.retention.check.interval.ms```，默认 300000) 检测不符合保留条件的日志文件，并且创建了 LogCleaner 用于 Log Compaction。

### Log Retention

基于 Retention 策略的日志清理会删除掉不符合保留条件的 LogSegment，清理条件包括 LogSegment 中消息的保留时间超过阈值、LogSegment 对应的日志文件大小超过阈值 和 ```baseOffset``` 小于 ```logStartOffset``` 的 LogSegment。

```java
// Log#deleteOldSegments

def deleteOldSegments(): Int = {
  if (config.delete) {
    deleteRetentionMsBreachedSegments() + deleteRetentionSizeBreachedSegments() + deleteLogStartOffsetBreachedSegments()
  } else {
    deleteLogStartOffsetBreachedSegments()
  }
}
```
```deleteRetentionMsBreachedSegments``` 方法用于删除日志文件中 timestamp 最大的消息的保留时间超过了设定阈值(retentionMs)的日志。阈值 ```retentionMs``` 由参数 ```retention.ms``` 参数设定，默认为 ```7*24*60*60*1000L```。
```java
private def deleteRetentionMsBreachedSegments(): Int = {
  if (config.retentionMs < 0) return 0
  val startMs = time.milliseconds
  // largestTimestamp 在每次写数据时更新为当前最大的时间戳
  // 当前时间 - largestTimestamp 即表示 Segment 中最新的数据的保留时间
  deleteOldSegments((segment, _) => startMs - segment.largestTimestamp > config.retentionMs,
    reason = s"retention time ${config.retentionMs}ms breach")
}
```
如果通过计算发现所有的 LogSegment 都已经过期，则需要先切分出一个新的 LogSegment 用于接收消息写入，然后再对 LogSegment 执行删除操作。

```deleteRetentionSizeBreachedSegments``` 方法用于清理日志文件超过设定阈值大小(retentionSize)的日志，阈值大小通过参数 ```retention.bytes``` 设置，默认值为 -1 表示无穷大。

日志删除任务会检查当前日志的大小是否超过设定的阈值(retentionSize)来寻找可删除的日志分段的文件集合(deletableSegments)。阈值大小可以通过 broker 端参数 ```log.retention.bytes``` 来配置，默认 -1 表示无穷大，该参数配置的所有日志分段文件的总大小而不是单个日志分段的大小，单个日志分段的大小由参数 ```log.segment.bytes``` 来控制，默认 173741824(1GB)。

基于日志大小保留策略首先计算日志文件总大小和 retentionSzie 的差值，然后从日志文件中的第一个日志分段开始查找可删除的日志分段集合，之后删除删除操作。


```java
private def deleteSegments(deletable: Iterable[LogSegment]): Int = {
  maybeHandleIOException(s"Error while deleting segments for $topicPartition in dir ${dir.getParent}") {
    val numToDelete = deletable.size
    if (numToDelete > 0) {
      // we must always have at least one segment, so if we are going to delete all the segments, create a new one first
      if (segments.size == numToDelete)
        roll()
      lock synchronized {
        checkIfMemoryMappedBufferClosed()
        // remove the segments for lookups
        removeAndDeleteSegments(deletable, asyncDelete = true)
        maybeIncrementLogStartOffset(segments.firstEntry.getValue.baseOffset)
      }
    }
    numToDelete
  }
}
```
LogSegment 的删除包含两部分：删除过期的 LogSegment 和 增加 Log 的 logStartOffset。删除过期的 LogSegmenet 操作首先从 Log 对象中维护的 LogSegment 的跳跃表中移除待删除的 LogSegment，保证没有线程对这些分段进行读取操作，然后将 LogSegment 对应的所有文件(日志文件和索引文件)添加 ```.delete``` 后缀，最后通过一个名为 ```delete-file``` 的延迟任务删除这些文件，任务的延迟时间由参数 ```file.delete.delay.ms``` 设置，默认是 60000。
```java
// Log#removeAndDeleteSegments

private def removeAndDeleteSegments(segments: Iterable[LogSegment], asyncDelete: Boolean): Unit = {
  lock synchronized {
    // As most callers hold an iterator into the `segments` collection and `removeAndDeleteSegment` mutates it by
    // removing the deleted segment, we should force materialization of the iterator here, so that results of the
    // iteration remain valid and deterministic.
    val toDelete = segments.toList
    // 从 Log 中维护的跳跃表中移除
    toDelete.foreach { segment =>
      this.segments.remove(segment.baseOffset)
    }
    // 删除对应的文件
    deleteSegmentFiles(toDelete, asyncDelete)
  }
}


// Log#deleteSegmentFiles

private def deleteSegmentFiles(segments: Iterable[LogSegment], asyncDelete: Boolean): Unit = {
  segments.foreach(_.changeFileSuffixes("", Log.DeletedFileSuffix))

  def deleteSegments(): Unit = {
    info(s"Deleting segments $segments")
    maybeHandleIOException(s"Error while deleting segments for $topicPartition in dir ${dir.getParent}") {
      segments.foreach(_.deleteIfExists())
    }
  }

  if (asyncDelete) {
    info(s"Scheduling segments for deletion $segments")
    scheduler.schedule("delete-file", () => deleteSegments, delay = config.fileDeleteDelayMs)
  } else {
    deleteSegments()
  }
}
```

#### 基于日志的起始偏移量

基于日志起始偏移量的保留策略的判断依据是某日志分段的下一个日志分段的起始偏移量是否小于日志的起始偏移量 logStartOffset，如果是则可以删除此日志分段。

### Compaction

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