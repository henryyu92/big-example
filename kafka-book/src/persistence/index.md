## 索引

为了提高消息查询的效率，Kafka 在将消息追加的日志的同时也会对消息建立索引。Kafka 提供了基于 `offset` 的偏移量索引和基于 `timestamp` 的时间戳索引。

- 偏移量索引：记录日志文件中消息的 offset 与物理存储的映射
- 时间戳索引：记录日志文件中消息的 `timestamp` 与消息的 offset 的映射

Kafka 以稀疏索引的方式为消息构造索引，不会为每个消息构造索引项，每写指定量的消息后才会构造对应的偏移量索引和时间戳索引，写入的消息量由参数 `log.index.interval.bytes` 参数控制，默认为 4096 B。

### 索引文件

Kafka 的索引文件和日志文件在相同的目录，并且索引文件名和日志文件名相同，为日志文件中第一条消息的 `offset`，表示当前日志文件的基准偏移量。

- 偏移量索引文件以 `.index` 为后缀，索引项的偏移量是单调递增的
- 时间戳索引文件以 `.timeindex` 为后缀，索引项的时间戳是单调递增的

日志文件在切分时对应的索引文件也需要切分形成新的索引文件，新索引文件的大小由参数 `log.index.size.max.bytes` 设置，默认为 10485760 B。

Kafka 索引文件通过 `MappedByteBuffer` 映射到内存中，通过二分查找法可以快速定位到指定的消息。

- 偏移量查找：通过二分查找定位不大于指定偏移量的最大偏移量索引项，然后根据索引项的物理地址顺序查找日志文件
- 时间戳查找：通过二分查找定位不大于指定时间戳的最大时间戳索引项，然后根据索引项的偏移量利用偏移量索引定位到顺序查找的起始物理地址，然后根据时间戳在日志文件中顺序查找

### 偏移量索引

偏移量索引(`OffsetIndex`) 记录的消息的 `offset` 与物理存储地址之间的关系，每个索引项(`IndexEntry`) 包含两部分内容：

- `relativeOffset`：消息的 offset 相对于对应的日志文件基准偏移的相对值，占用 4 个字节
- `position`：消息在日志文件中的物理位置，占用 4 个字节

由于 `relativeOffset` 只占用 4 个字节，因此每个偏移量索引文件中最多只能记录 `Integer.MAX_VALUE` 个索引。

Broker 在根据 offset 查找消息时，首先需要根据 offset 找到对应的 `LogSegment`，然后在 LogSegment 对应的偏移量索引文件中根据计算的 `relativeOffset` 查找消息的物理地址，最后根据物理地址在对应的 LogSegment 中顺序查找。

Kafka 没有采用顺序查找的方式找到 offset 对应的 LogSegment，而是采用了跳跃表来快速查找，其中 key 是 LogSegment 的基准偏移量(第一个消息的 offset)，value 则是对应的 LogSegment。通过跳跃表查询可以快速定位到不大于查找的 offset 的最大基准偏移量的 LogSegment，即消息所在的 LogSegment。

### 时间戳索引

时间戳索引 (TimeIndex) 中记录了消息的 timestamp 和消息的 offset 的对应关系，时间戳索引中的每个索引项占用 12 个字节，包含两部分内容：
- `timestamp`：当前日志分段最大的时间戳，占用 8 字节
- `relativeOffset`：时间戳对应的消息的相对偏移量，占用 4 字节

时间戳索引也是单调递增的，追加的索引项的 timestamp 必须大于之前追加的索引项的 timestamp，```log.message.timestamp.type``` 设置为 ```LogAppendTime``` 则可以保证 timestamp 单调递增；如果设置为 ```CreateTime```，除非生产者在生产消息时能够指定单调递增的时间戳，否则时间戳索引无法保证会被写入。

偏移量索引 (OffsetIndex) 和时间戳索引 (TimeIndex) 增加索引项操作是同时进行的，但是偏移量索引项中的 relativeOffset 和时间戳索引项中的 relativeOffset 不一定是同一个值，因为写入偏移量索引的 offset 是消息集中最大的 offset，而写入时间戳索引的 offset 是消息集中 timestamp 最大的消息的 offset。

broker 根据 timestamp 查找消息时首先需要根据 timestamp 定位到消息所在的 LogSegment，然后在 LogSegment 中先根据 timestamp 在 TimeIndex 时间戳索引找到 timestamp 对应的 offset，然后在 OffsetIndex 偏移量索引中根据 offset 找到消息所在的 postion，最后在 LogSegment 中从 position 开始遍历查找指定 timestamp 的消息。

根据 timestamp 定位消息所在的 LogSegment 时是遍历每个 LogSegment，将 LogSegment 中的 largestTimestamp 与查找的 timestamp 比较，找到所有 largestTimestamp 小于查找的 timestamp 的 LogSegment。LogSegment 中的 largestTimestamp 在每次写入时间戳索引时更新为时间戳索引中的 timestamp。



确定到 LogSegment 之后遍历这些 LogSegment 并通过二分查找算法找到不大于指定的 timestamp 的最大索引项，然后根据找到的索引项中的 relativeOffset 在偏移量索引中通过二分查找找到不大于 relativeOffset 的最大索引项，最后根据 postion，在 LogSegment 中从 position 位置开始查找指定的 timestamp 的消息。 

