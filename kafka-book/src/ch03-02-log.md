# 日志

消息在 Kafka 集群中以追加日志的方式存储在磁盘，每个分区都有对应的日志文件，日志文件位于配置参数 `log.dir` 指定的目录下的 `<topic>-<partition>` 目录中。每个分区对应的目录下有多个日志文件，日志文件名表示持久化在其中的消息的基准偏移量。
```
集群主题比较多，分区比较多是会导致文件很多，消息写入的时候写入多个文件夹，不再是顺序写了
```

## Log

Log 是 Kafka 日志的实现，客户端发送的消息通过 Log 实现持久化存储。

Log 由多个分段 (Segment) 组成，每个分段负责维护部分消息数据。分段中只有一个处于活跃状态 (activeSegment) 用于消息的追加，当处于活跃状态的分段达到阈值条件后会滚动创建新的分段。
<center>

![Log](img/log.png)
</center>

日志分段是 Kafka 中最基本的数据存储单元，每个分段对应着一个物理文件。

```scala
class LogSegment private[log] (val log: FileRecords,
                               val lazyOffsetIndex: LazyIndex[OffsetIndex],
                               val lazyTimeIndex: LazyIndex[TimeIndex],
                               val txnIndex: TransactionIndex,
                               val baseOffset: Long,
                               val indexIntervalBytes: Int,
                               val rollJitterMs: Long,
                               val time: Time)
```




### 消息追加

客户端发送的消息在追加到 Log 时会追加到其中活跃的 Segment，当 Segment 的大小达到阈值之后会滚动创建一个新的 Segment 并且关闭之前的 Segment。

```scala
private def append(records: MemoryRecords,
                     origin: AppendOrigin,
                     interBrokerProtocolVersion: ApiVersion,
                     assignOffsets: Boolean,
                     leaderEpoch: Int): LogAppendInfo = {
                     
}
```
集群接收的消息由 Log 追加到 Segment 中，，当 segment 的大小到达阈值大小之后，会滚动新建一个日志分段（segment）保存新的消息，而分区的消息总是追加到最新的日志分段（也就是 activeSegment）中。每个日志分段都会有一个基准偏移量（segmentBaseOffset，或者叫做 baseOffset），这个基准偏移量就是分区级别的绝对偏移量，而且这个值在日志分段是固定的。有了这个基准偏移量，就可以计算出来每条消息在分区中的绝对偏移量，最后把数据以及对应的绝对偏移量写到日志文件中。






### 日志切分
消息在追加到活跃分段时会计算当前日志是否需要切分新的分段，

```scala
// Log
  private def maybeRoll(messagesSize: Int, appendInfo: LogAppendInfo): LogSegment = {
    val segment = activeSegment
    val now = time.milliseconds

    val maxTimestampInMessages = appendInfo.maxTimestamp
    val maxOffsetInMessages = appendInfo.lastOffset

    if (segment.shouldRoll(RollParams(config, appendInfo, messagesSize, now))) {
      debug(s"Rolling new log segment (log_size = ${segment.size}/${config.segmentSize}}, " +
        s"offset_index_size = ${segment.offsetIndex.entries}/${segment.offsetIndex.maxEntries}, " +
        s"time_index_size = ${segment.timeIndex.entries}/${segment.timeIndex.maxEntries}, " +
        s"inactive_time_ms = ${segment.timeWaitedForRoll(now, maxTimestampInMessages)}/${config.segmentMs - segment.rollJitterMs}).")

      /*
        maxOffsetInMessages - Integer.MAX_VALUE is a heuristic value for the first offset in the set of messages.
        Since the offset in messages will not differ by more than Integer.MAX_VALUE, this is guaranteed <= the real
        first offset in the set. Determining the true first offset in the set requires decompression, which the follower
        is trying to avoid during log append. Prior behavior assigned new baseOffset = logEndOffset from old segment.
        This was problematic in the case that two consecutive messages differed in offset by
        Integer.MAX_VALUE.toLong + 2 or more.  In this case, the prior behavior would roll a new log segment whose
        base offset was too low to contain the next message.  This edge case is possible when a replica is recovering a
        highly compacted topic from scratch.
        Note that this is only required for pre-V2 message formats because these do not store the first message offset
        in the header.
      */
      appendInfo.firstOffset match {
        case Some(firstOffset) => roll(Some(firstOffset))
        case None => roll(Some(maxOffsetInMessages - Integer.MAX_VALUE))
      }
    } else {
      segment
    }
  }

// LogSegment
  def shouldRoll(rollParams: RollParams): Boolean = {
    val reachedRollMs = timeWaitedForRoll(rollParams.now, rollParams.maxTimestampInMessages) > rollParams.maxSegmentMs - rollJitterMs
    size > rollParams.maxSegmentBytes - rollParams.messagesSize ||
      (size > 0 && reachedRollMs) ||
      offsetIndex.isFull || timeIndex.isFull || !canConvertToRelativeOffset(rollParams.maxOffsetInMessages)
  }


```

```scala
def roll(expectedNextOffset: Option[Long] = None): LogSegment = {
    maybeHandleIOException(s"Error while rolling log segment for $topicPartition in dir ${dir.getParent}") {
      val start = time.hiResClockMs()
      lock synchronized {
        checkIfMemoryMappedBufferClosed()
        val newOffset = math.max(expectedNextOffset.getOrElse(0L), logEndOffset)
        val logFile = Log.logFile(dir, newOffset)

        if (segments.containsKey(newOffset)) {
          // segment with the same base offset already exists and loaded
          if (activeSegment.baseOffset == newOffset && activeSegment.size == 0) {
            // We have seen this happen (see KAFKA-6388) after shouldRoll() returns true for an
            // active segment of size zero because of one of the indexes is "full" (due to _maxEntries == 0).
            warn(s"Trying to roll a new log segment with start offset $newOffset " +
                 s"=max(provided offset = $expectedNextOffset, LEO = $logEndOffset) while it already " +
                 s"exists and is active with size 0. Size of time index: ${activeSegment.timeIndex.entries}," +
                 s" size of offset index: ${activeSegment.offsetIndex.entries}.")
            removeAndDeleteSegments(Seq(activeSegment), asyncDelete = true)
          } else {
            throw new KafkaException(s"Trying to roll a new log segment for topic partition $topicPartition with start offset $newOffset" +
                                     s" =max(provided offset = $expectedNextOffset, LEO = $logEndOffset) while it already exists. Existing " +
                                     s"segment is ${segments.get(newOffset)}.")
          }
        } else if (!segments.isEmpty && newOffset < activeSegment.baseOffset) {
          throw new KafkaException(
            s"Trying to roll a new log segment for topic partition $topicPartition with " +
            s"start offset $newOffset =max(provided offset = $expectedNextOffset, LEO = $logEndOffset) lower than start offset of the active segment $activeSegment")
        } else {
          val offsetIdxFile = offsetIndexFile(dir, newOffset)
          val timeIdxFile = timeIndexFile(dir, newOffset)
          val txnIdxFile = transactionIndexFile(dir, newOffset)

          for (file <- List(logFile, offsetIdxFile, timeIdxFile, txnIdxFile) if file.exists) {
            warn(s"Newly rolled segment file ${file.getAbsolutePath} already exists; deleting it first")
            Files.delete(file.toPath)
          }

          Option(segments.lastEntry).foreach(_.getValue.onBecomeInactiveSegment())
        }

        // take a snapshot of the producer state to facilitate recovery. It is useful to have the snapshot
        // offset align with the new segment offset since this ensures we can recover the segment by beginning
        // with the corresponding snapshot file and scanning the segment data. Because the segment base offset
        // may actually be ahead of the current producer state end offset (which corresponds to the log end offset),
        // we manually override the state offset here prior to taking the snapshot.
        producerStateManager.updateMapEndOffset(newOffset)
        producerStateManager.takeSnapshot()

        val segment = LogSegment.open(dir,
          baseOffset = newOffset,
          config,
          time = time,
          fileAlreadyExists = false,
          initFileSize = initFileSize,
          preallocate = config.preallocate)
        addSegment(segment)

        // We need to update the segment base offset and append position data of the metadata when log rolls.
        // The next offset should not change.
        updateLogEndOffset(nextOffsetMetadata.messageOffset)

        // schedule an asynchronous flush of the old segment
        scheduler.schedule("flush-log", () => flush(newOffset), delay = 0L)

        info(s"Rolled new log segment at offset $newOffset in ${time.hiResClockMs() - start} ms.")

        segment
      }
    }
  }

```

日志分段文件在一定条件会切分，相对应的索引文件也需要切分。日志分段文件切分包含以下一条即可触发切分：
- 当前日志分段文件的大小超过了 broker 端参数 ```log.segment.bytes``` 配置的值，默认是 1073741824(1GB)
- 当前日志分段中消息的最大时间戳与当前系统的时间戳的差值大于 ```log.roll.ms``` 或 ```log.roll.hours``` 参数配置的值(log.roll.ms 优先级大于 log.roll.hours)，默认情况下只配了 ```log.roll.hours``` 值为 168(7 天)
- 偏移量索引文件或时间戳索引文件的大小达到 broker 端参数 ```log.index.size.max.bytes``` 配置的值，默认是 10485760(10MB)
- 追加的消息的偏移量与当前日志分段的偏移量之间的差值大于 Integer.MAX_VALUE，即 ```offset - baseOffset > Integer.MAX_VALUE```

