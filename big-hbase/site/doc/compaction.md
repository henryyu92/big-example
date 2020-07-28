## Compaction
HFile 小文件如果数量太多会导致读取效率低，为了提高读取效率，LSM 树体系架构设计了 Compaction，Compaction 的核心功能是将小文件合并成大文件，提高读取效率。

Compaction 是从一个 Region 的一个 Store 中选择部分 HFile 文件进行合并，先从这些待合并的数据文件中依次读出 KeyValue，再由小到大排序后写入一个新的文件，之后新生成的文件就会取代之前已经合并的所有文件对外提供服务。

HBase 根据合并规模将 Compaction 分为两类：
- Minor Compaction：选取部分小的、相邻的 HFile，将它们合并成一个更大的 HFile
- Major Compaction：将一个 Store 中所有的 HFile 合并成一个 HFile，这个过程会完全清理掉被删除的数据、TTL 过期数据、版本号超过设定版本号的数据。一般情况下 Major Compaction 持续时间比较长，消耗的资源比较多，对业务有比较大的影响，因此线上数据量比较大的通常推荐关闭自动触发 Major Compaction 功能，而是在业务低峰期手动触发

Compaction 有以下核心作用：
- 合并小文件，减少文件数，稳定随机读延迟
- 提供数据的本地化率，本地化率越高，在 HDFS 上访问数据时延迟就越小(不需要通过网络访问)
- 清除无效数据，减少数据存储量

Compaction 在执行过程中有个比较明显的副作用：Compaction 操作重写文件会带来很大的带宽压力以及短时间 IO 压力，Compaction 过程需要将小文件数据跨网络传输，然后合并成一个大文件再次写入磁盘。因此 Compaction 操作可以认为是用短时间的 IO 消耗以及贷款消耗换取后续查询的低延迟，从读取响应时间图上可以看到读取响应时间由比较大的毛刺，这是因为 Compaction 在执行的时候占用系统资源导致业务读取性能受到波及。

HBase 中 Compaction 只有在特定的触发条件才会执行，一旦触发就会按照特定的流程执行 Compaction。HBase 会将该 Compaction 交由一个独立的线程处理，该线程首先会从对应 Store 中选择合适的 HFile 文件进行合并，选取文件有多种算法，如：RatioBasedCompactionPolicy、ExploringCompactionPolocy 和 StripeCompactionPolicy 等，也可以自定义实现 Compaction 接口实现自定义的策略，挑选出合适的文件后，HBase 会根据这些 HFile 总大小挑选对应的线程池处理，最后对这些文件执行具体的合并操作。

### Compaction 触发时机
HBase 中触发 Compaction 的时机很多，最常见的有三种：
- MemStore Flush：MemStore Flush 会产生 HFile 文件，在每次执行完 flush 操作之后都会对当前 Store 中的文件数进行判断，一旦 Store 中总文件数大于 hbase.hstore.compactionThreshold 就会触发 Compaction。Compaction 是以 Store 为单位进行的，但是在 flush 触发条件下整个 Region 的所有 Store 都会执行 compaction 检查，所以一个 Region 有可能会在短时间内执行多次 Compaction
- 后台线程周期性检查：RegionServer 在启动时在后台启动一个 CompactionChecker 线程，用于定期触发检查对应 Store 是否需要执行 Compaction，检查周期为 hbase.server.thread.wakefrequency * hbase.server.compactchecker.interval.multiplier。和 flush 不同的是，该线程优先检查 Store 中总文件数是否大于阈值 hbase.hstore.compactionThreshold，一旦大于就会触发 Compaction；如果不满足，接着检查是否满足 MajorCompaction 条件，如果当前 Store 中 HFile 的最早更新时间早于某个值(hbase.hregion.majorcompaction*(1+hbase.hregion.majorcompaction.jitter))，默认是 7 天
- 手动触发：手动触发 Compaction 大多是为了执行 Major Compaction，Major Compaction 会比较大的影响业务性能且可以删除无效数据，可以在需要的时候手动触发 Major Compaction

### HFile 集合选择策略
选择合适的文件进行合并是整个 Compaction 的核心，因为合并文件的大小及其当前承载的 IO 数直接决定了 Compaction 的效果以及对整个系统业务的影响程度。

HBase 提供的选择策略会首先对该 Store 中所有 HFile 逐一进行排查，排除不满足条件的部分文件：
- 排除当前正在执行 Compaction 的文件以及比这些文件更新的文件
- 排除某些过大的文件，如果文件大于 hbase.hstore.compaction.max.size 则排除，默认是 Long.MAX_VALUE

经过排除后留下来的文件称为候选文件，接下来 HBase 再判断候选文件是否满足 Major Compaction 条件，如果满足就会选择全部文件进行合并。只要满足其中一个条件就会执行 Major Compaction：
- 用户强制执行 Major Compaction
- 长时间没有进行 Major Compaction(上次执行 Major Compaction 的时间早于当前时间 - hbase.hregion.majorcompaction)且候选文件数小于 hbase.hstore.compaction.max，默认 10
- Store 中含有 reference 文件(Region 分裂产生的临时文件，需要在 Compaction 过程中清理)

如果满足 Major Compaction 则待合并的 HFile 就是 Store 中所有的 HFile，如果不满足则 HBase 需要继续采用文件选择策略选择合适的 HFile。HBase 主要有两种文件选择策略：RatioBasedCompactionPolicy 和 ExploringCompactionPolicy

#### RatioBasedCompactionPolicy
从老到新逐一扫描所有候选文件，满足任意一个条件就停止扫描：
- 当前文件大小小于比当前文件新的所有文件大小总和 * ratio
- 当前所剩候选文件数小于等于 hbase.store.compaction.min，默认为 3

停止扫描后，待合并的文件就是当前扫描文件以及比它更新的所有文件
#### ExploringCompactionPolicy
Exploring 策略会记录所有合适的文件结合，并在这些文件集合中寻找最优解，即待合并文件数最多或者待合并文件数相同的情况下文件较小

### 挑选合适的执行线程池
选择出了待合并的文件集合之后，需要挑选出合适的处理线程来进行正真的合并操作。HBase 实现了一个专门的类 CompactSplitThread 负责接收 Compaction 请求和 split 请求，为了能够独立处理这些请求，这个类内部构造了多个线程池：
- longCompactions：处理大 Compaction，大 Compaction 并不是 Major Compaction，而是 Compaction 的总文件大小超过 hbase.regionserver.thread.compaction.throttle，且 longCompactions 线程池默认只有 1 个线程，使用参数 hbaes.regionserver.thread.compaction.large 设置
- shortCompactions：处理小 Compaction
- splits：负责处理所有的 split 请求

### HFile 文件合并
选出待合并的 HFile 集合以及合适的处理线程，接下来就执行合并流程，总共分为 4 步：
- 分别读出待合并 HFile 文件的 KeyValue，进行归并排序处理，之后写到 ./tmp 目录下的临时文件中
- 将临时文件移动到对应 Store 的数据目录
- 将 Compaction 的输入文件路径和输出文件路径封装成 KV 写入 HLog 日志，并打上 Compaction 标记，最后强制执行 sync
- 将对应 Store 数据目录下的 Compaction 输入文件全部删除

HFile 合并流程具有很强的容错性和幂等性：
- 如果在移动临时文件到数据目录前 RegionServer 发生异常，则后续同样的 Compaction 并不会收到影响，只是 ./tmp 目录下会多一份临时文件
- 如果在写入 HLog 日志之前 RegionServer 发生异常，则只是 Store 对应的数据目录下多了一份数据，而不会影响真正的数据
- 如果在删除 Store 数据目录下的 Compaction 输入文件之前 RegionServer 异常，则 RegionServer 在重新打开 Region 之后首先会从 HLog 中看到标有 Compaction 的日志，只需要根据 HLog 移除 Compaction 输入文件即可

对文件进行 Compaction 操作可以提升业务读取性能，但是如果不对 Compaction 执行阶段的读写吞吐量进行限制，可能会引起短时间大量系统资源消耗，从而发生读写延迟抖动。可采用如下优化方案：

Limit Compaction Speed 优化方案通过感知 Compaction 的压力情况自动调节系统的 Compaction 吞吐量：
- 在正常情况下，设置吞吐量下限参数 hbase.hstore.compaction.throughput.lower.bound 和上限参数 hbase.hstore.compaction.throughput.higher.bound，实际吞吐量为 lower+(higher-lower)*ratio
- 如果当前 Store 中 HFile 的数量太多，并且超过了参数 blockingFileCount，此时所有写请求就会被阻塞以等待 Compaction 完成

Compaction 除了带来严重的 IO 消耗外，还会因为大量消耗带宽资源而严重影响业务：
- 正常请求下，Compaction 尤其是 Major Compaction 会将大量数据文件合并为一个大 HFile，读出所有数据文件的 KV，重新排序后写入另一个新建的文件，如果文件不在本地则需要跨网络进行数据读取，需要消耗网络带宽
- 数据写入文件默认都是三副本写入，不同副本位于不同节点，因此写的时候会跨网络执行，必然会消耗网络带宽

Compaction BandWidth Limit 优化方案主要设计两个参数：
- compactBwLimit：一次 Compaction 的最大带宽使用量，如果 Compaction 所使用的带宽高于该值，就会强制其 sleep 一段时间
- numOfFileDisableCompactLimit：在写请求非常大的情况下，限制 Compaction 带宽必然会导致 HFile 堆积，进而会影响读请求响应延迟，因此一旦 Store 中 HFile 数据超过设定值，带宽限制就会失效

### Compaction 高级策略
Compaction 合并小文件，一方面提高了数据的本地化率，降低了数据读取的响应延时，另一方面也会因为大量消耗系统资源带来不同程度的短时间读取响应毛刺。HBase 提供了 Compaction 接口，可以根据自己的应用场景以及数据集订制特定的 Compaction 策略，优化 Compaction 主要从几个方面进行：
- 减少参与 Compaction 的文件数，需要将文件根据 rowkey、version 或其他属性进行分割，再根据这些属性挑选部分重要的文件参与合并，并且尽量不要合并那些大文件
- 不要合并那些不需要合并的文件，比如基本不会被查询的老数据，不进行合并也不会影响查询性能
- 小 Region 更有利于 Compaction，大 Region 会生成大量文件，不利于 Compaction

#### FIFO Compaction
FIFO Compaction 策略参考了 RocksDB 的实现，它选择过期的数据文件，即该文件内的所有数据均已经过期，然后将收集到的文件删除，因此适用于此 Compaction 策略的对应列簇必须设置 TTL。

FIFO Compaction 策略适用于大量短时间存储的原始数据的场景，比如推荐业务、最近日志查询等。FIFO Compaction 可以在表或者列簇上设置：
```java
Connection conn = ConnectionFactory.createConnection();

HTableDescriptor desc = conn.getAdmin().getTableDescriptor(TableName.valueOf(""));
desc.setConfiguration(DefaultStoreEngine.DEFAULT_COMPACTOR_CLASS_KEY, FIFOCompactionPolicy.class.getName());

HColumnDescriptor colDesc = desc.getFamily("family".getBytes());
colDesc.setConfiguration(DefaultStoreEngine.DEFAULT_COMPACTOR_CLASS_KEY, FIFOCompactionPolicy.class.getName());
```

#### Tier-Based Compaction
在现实业务中，有很大比例的业务数据都存在明显的热点数据，最近写入的数据被访问的频率比较高，针对这种情况，HBase 提供了 Tire-Based Compaction。这种方案根据候选文件的新老程度将其分为不同的等级，每个等级都有对应的参数，比如 Compaction Ratio 表示该等级的文件的选择几率。通过引入时间等级和 Compaction Ratio 等概念，就可以更加灵活的调整 Compaction 效率。

Tire-Based Compaction 方案基于时间窗的概念，当时间窗内的文件数达到 threshold (可通过参数 min_threshold 配置)时触发 Compaction 操作，时间窗内的文件将会被合并为一个大文件。基于时间窗的 Compaction 可以通过调整窗口大小来调整优先级，但是没有一个窗口时间包括所有文件，因此这个方案没有 Major Compaction 的功能，只能借助于 TTL 来清理过期文件。

Tire-Based Compaction 策略适合下面的场景：
- 时间序列数据，默认使用 TTL 删除，同时不会执行 delete 操作（特别适合）
- 时间序列数据，有全局更新操作以及少部分删除操作（比较适合）

#### Level Compaction
Level Compaction 设计思路是将 Store 中的所有数据划分为很多层，每一层有一部分数据。

数据不再按照时间先后进行组织，而是按照 KeyRange 进行组织，每个 KeyRange 进行包含多个文件，这些文件所有数据的 Key 必须分布在同一个范围。

整个数据体系会被划分为很多层，最上层(Level 0)表示最新的数据，最下层(Level 6)表示最旧数据，每一层都由大量 KeyRange 块组成，KeyRnage 之间没有重合。层数越大，对应层的每个 KeyRange 块越大，下层 KeyRange 块大小是上一层的 10 倍，数据从 MemStore 中 flush 之后，会先落入 Level 0，此时落入 Level 0 的数据可能包含所有可能的 Key，此时如果需要执行 Compaction，只需要将 Level 0 中的 KV 逐个读取出来，然后按照 Key 的分布分别插入 Level 1 中对应的 KeyRange 块的文件中，如果此时刚好 Level 1 中的某个 KeyRnage 块大小超过了阈值，就会继续往下一层合并

Level Compaction 中依然存在 Major Compaction 的概念，发生 Major Compaction 只需要将部分 Range 块内的文件执行合并就可以，而不需要合并整个 Region 内的数据文件。

这种合并策略实现中，从上到下只需要部分文件参与，而不需要对所有文件执行 Compaction 操作。另外，对于很多“只读最近写入的数据”的业务来说，大部分读请求都会落到 Level 0，这样可以使用 SSD 作为上层 Level 存储介质，进一步优化读。但是 Level Compaction 因为层数太多导致合并的次数明显增多，对 IO 利用率并没有显著提升。

#### Stripe Compaction
Stripe Compaction 和 Level Compaction 原理相同，会将整个 Store 中的文件按照 key 划分为多个 Range，称为 stripe。stripe 的数量可以通过参数设定，相邻的 stripe 之间 key 不会重合。实际上从概念上看，stripe 类似于将一个大的 Region 划分成的小 Region。

随着数据写入，MemStore 执行 flush 形成 HFile，这些 HFile 并不会马上写入对应的 stripe，而是放到一个称为 L0 的地方，用户可以配置 L0 放置 HFile 的数量。一旦 L0 放置的文件数超过设定值，系统会将这些 HFile 写入对应的 stripe：首先读出 HFile 的 KV，再根据 key 定位到具体的 stripe，将 KV 插入对应的 stripe 文件中。stripe 就是一个小的 Region，因此在 stripe 内部依然会有 Minor Compaction 和 Major Compaction，stripe 由于数据量并不是很大，因此 Major Compaction 并不会消耗太多资源。另外，数据读取可以根据对应的 key 查找到对应的 stripe，然后在 stripe 内部执行查找，因为 stripe 的数据量相对较小，所以也会在一定程度上提升数据查找性能。

Stripe Compaction 有两种比较擅长的业务场景：
- 大 Region。小 Region 没有必要切分为 stripe，一旦切分反而会带来额外的管理开销，一般默认 Region 小于 2G，就不适合使用 Stripe Compaction
- Rowkey 具有统一格式，Stripe Compaction 要求所有数据按照 key 进行切分，生成多个 stripe，如果 rowkey 不具有统一格式，则无法切分