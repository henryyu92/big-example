## LogManager

### 日志刷盘

Kafka 依赖文件系统来存储消息，采用文件追加的方式来写入消息，即只能在日志文件的尾部追加新的消息，并且不允许修改已写入的消息。

Kafka 消息写入时会先写入页缓存，然后由操作系统负责具体的刷盘任务，通过设置参数 `log.flush.interval.message` 和 `log.flush.interval.ms` 可以强制控制数据刷盘的速率。

### 日志清理

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

```deleteRetentionSizeBreachedSegments``` 方法用于清理超过一定大小的日志。该算法首先计算 Log 的总大小和阈值的差值 diff，阈值通过参数 ```retention.bytes``` 设置，默认值为 -1 表示无穷大。如果差值 diff 大于 0，则从第一个 LogSegment 开始找出需要清理的 LogSegment。

```java
private def deleteRetentionSizeBreachedSegments(): Int = {
  if (config.retentionSize < 0 || size < config.retentionSize) return 0
    // 计算 Log 总大小和 retensionSize 的差值
    var diff = size - config.retentionSize
    def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment]) = {
      // 如果 LogSegment 大小小于差值则需要清理
      if (diff - segment.size >= 0) {
        diff -= segment.size
        true
      } else {
        false
      }
    }

    deleteOldSegments(shouldDelete, reason = s"retention size in bytes ${config.retentionSize} breach")
}
```

```deleteLogStartOffsetBreachedSegments``` 方法用于删除所有消息的 offset 小于 Log 的 logStartOffset 的 LogSegment。算法计算 LogSegment 的下一个 LogSegment 的 baseOffset 是否小于 Log 的 logStartOffset，如果是则表示是该 LogSegment 可以删除：

```java
private def deleteLogStartOffsetBreachedSegments(): Int = {
  def shouldDelete(segment: LogSegment, nextSegmentOpt: Option[LogSegment]) =
    nextSegmentOpt.exists(_.baseOffset <= logStartOffset)

  deleteOldSegments(shouldDelete, reason = s"log start offset $logStartOffset breach")
}
```

日志删除策略确定的 LogSegment 除了需要满足策略外，还需要满足删除的 LogSegment 中的消息的 offset 是小于 Log 的 HW 的，并且删除的 LogSegment 不能是 Log 的最后一个 LogSegment：

```java
private def deletableSegments(predicate: (LogSegment, Option[LogSegment]) => Boolean): Iterable[LogSegment] = {
  if (segments.isEmpty) {
    Seq.empty
  } else {
    val deletable = ArrayBuffer.empty[LogSegment]
    var segmentEntry = segments.firstEntry
    while (segmentEntry != null) {
      val segment = segmentEntry.getValue
      val nextSegmentEntry = segments.higherEntry(segmentEntry.getKey)
      val (nextSegment, upperBoundOffset, isLastSegmentAndEmpty) = if (nextSegmentEntry != null)
        (nextSegmentEntry.getValue, nextSegmentEntry.getValue.baseOffset, false)
      else
        (null, logEndOffset, segment.size == 0)

      if (highWatermark >= upperBoundOffset && predicate(segment, Option(nextSegment)) && !isLastSegmentAndEmpty) {
        deletable += segment
        segmentEntry = nextSegmentEntry
      } else {
        segmentEntry = null
      }
    }
    deletable
  }
}
```

确定了需要删除的 LogSegment 之后就可以删除过期的 LogSegment，删除之前需要保证 Log 中最少有一个 LogSegment，因此当需要删除的 LogSegment 数量等于 Log 中 LogSegment 数量时会再创建出一个 LogSegment。

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
        // 调整日志的 LogStartOffset
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

### Log Compaction
<center>

![Log Compaction](../img/log-compaction.png)
</center>


Log Compaction 清理有重复 key 的消息，只保留最新的消息。开启 Log Compaction 是由参数 ```log.cleaner.enable``` 设置的，默认为 true。

Kafka LogMananger 在启动时会启动 LogCleaner，并在启动时创建 numThreads (参数 ```log.cleaner.threads```
设置，默认 1) 个 CleanerThread 线程用于执行 LogComapction。

在 ```CleanThread``` 的 ```doWork``` 方法中先通过 CleanManager 获取可以 Compact 的 Log，然后执行 Compact 操作，如果 Compact 失败则采取回退算法休眠：

```java
override def doWork(): Unit = {
  val cleaned = tryCleanFilthiestLog()
  if (!cleaned)
    pause(config.backOffMs, TimeUnit.MILLISECONDS)
}
```

Kafka 日志存储目录内有 ```cleaner-offset-checkpoint``` 文件记录当前分区中已经清理的 offset，通过这个文件可以将 Log 分为已经清理过的部分和未清理的部分。然后在 ```LogCleanerManager#cleanableOffsets``` 方法中在未清理的部分中定位可以清理的范围 ```[firstDirtyOffset, firstUnstableOffset)```。

定位可以清理的范围的时候需要排除 activeSegment，同时可以设置参数 ```log.cleaner.min.compaction.lag.ms``` 来配置消息在清理前最小的保留时间来排除消息所在的 Segment。

```LogCleannerManager#grabFilthiestCompactedLog``` 方法对定位的 offset 范围再次过滤，过滤掉  ```cleanableRatio``` 小于参数 ```log.cleaner.min.cleanable.ratio``` 设置的值的 LogSegment，默认为 0.5。 最后选出 ```cleanableRatio``` 最高的 Log 进行清理：

```java
// LogCleannerManager#grabFilthiestCompactedLog

val cleanableLogs = dirtyLogs.filter { ltc => 
  (ltc.needCompactionNow && ltc.cleanableBytes > 0) || ltc.cleanableRatio > ltc.log.config.minCleanableRatio
  // 计算 celanableRatio 并排除掉小于 log.cleaner.min.cleanable.ratio 的 LogSegment
}
if(cleanableLogs.isEmpty) {
  None
} else {
  preCleanStats.recordCleanablePartitions(cleanableLogs.size)
  // 清理 celanableRatio 最大的 LogSegment
  val filthiest = cleanableLogs.max
  inProgress.put(filthiest.topicPartition, LogCleaningInProgress)
  Some(filthiest)
}


// LogCleaner

// firstDirtyOffset 之前的 LogSegment 的大小之和
val cleanBytes = log.logSegments(-1, firstDirtyOffset).map(_.size.toLong).sum
// 可清理的 LogSegment 的大小之和
val (firstUncleanableOffset, cleanableBytes) = LogCleanerManager.calculateCleanableBytes(log, firstDirtyOffset, uncleanableOffset)
val totalBytes = cleanBytes + cleanableBytes
val cleanableRatio = cleanableBytes / totalBytes.toDouble
```

LogCleaner 在创建 CleanerThread 线程清理日志时，每个日志清理线程都会创建一个 ```SkimpyOffsetMap``` 对象来映射日志中消息的 key 和 消息的 offset。日志清理线程需要遍历两次文件，第一次遍历把消息的 key 和 最后出现的 offset 保存在 ```SkimpyOffsetMap``` 对象中，第二次遍历会检查每个消息是否需要清理，如需要则清理。


默认情况下，SkimpyOffsetMap 使用 MD5 来计算 key 的哈希值，占用空间大小为 16B，根据这个哈希值来从 SkimpyOffsetMap 中找到对应的槽位，如果发生冲突则用线性探测法处理。为了防止哈希冲突过于频繁，可以通过 ```log.cleaner.io.buffer.load.factor```(默认 0.9) 来调整负载因子。偏移量占用空间大小为 8B，因此一个映射项占用空间大小为 24B。

每个日志清理线程的 SkimpyOffsetMap 的内存占用大小为 ```log.cleaner.dedupe.buffer.size / log.cleaner```，默认情况下 SkimpyOffsetMap 可以保存 ```128M * 0.9/24B=5033164``` 个 key 的记录。

#### 墓碑消息

Log Compaction 会保留 key 相应的最新 value 值，当需要删除一个 key 时 Kafka 提供了墓碑消息(tombstone)的概念，如果一条消息的 key 不为 null，但是其 value 为 null，那么此消息就是墓碑消息。

日志清理线程发现墓碑消息时会先进行常规的清理并保留墓碑消息一段时间，墓碑消息的保留条件是当前墓碑消息所在的日志分段的最近修改时间 lastModifiedTime 大于 deleteHorizonMs，这个 deleteHorizonMs 的计算方式为 clean 部分中最后一个日志分段的最近修改时间减去保留阈值 deleteRetionMs（通过 log.cleaner.delete.retention.ms 配置，默认值 86400000）

Log Compaction 执行过后的日志分段的大小会比原先的日志分段文件小，为了防止出现太多的小文件，Kafka 在实际清理过程中并不对单个日志分段文件进行单独清理，而是将日志分段文件中 offset 从 0 到 firstUncleanableOffset 的所有日志分段进行分组，每个日志分段属于一组，分组策略为：按照日志分段的顺序遍历，每组中日志分段的占用空间大小之和不能超过 segmentSize (通过 log.segment.bytes 设置，默认 1GB)，且对应的索引文件占用大小之和不超过 maxIndexSize（通过 log.index.interval.bytes 设置，默认 10MB）。同一个组的多个日志分段清理过后只会生成一个新的日志分段。

Log Compaction 过程中会将每个日志分组中需要保留的消息复制到一个以 .clean 为后缀的临时文件中，此临时文件以当前日志分组中第一个日志分段的文件命名，如 00000000000000000000.log.clean。Log Compaction 之后将 .clean 的文件修改为 .swap 后缀的文件，然后删除原本的日志文件，最后把文件的 .swap 后缀去掉

Log Compaction 执行前后日志分段中的每条消息的偏移量和写入时的偏移量保持一致，Log Compaction 会生成新的日志分段文件，日志分段中每条消息的物理地址会重新按照新文件来组织。Log Compaction 执行之后的偏移量不再是连续的，但是并不影响日志的查询。