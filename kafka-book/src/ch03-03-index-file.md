# 索引文件


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