### Compaction

`Compaction` 是从 Store 中选择部分 HFile 文件依次读取出存储的 KeyValue，然后排序之后写入一个新的 HFile 文件。

HBase 根据参与合并文件的规模将 Compaction 操作分为两类：

- `Minor Compaction`：选取 Store 的部分 HFile 合并成一个更大的 HFile
- `Major Compaction`：Store 中的所有 HFile 合并成一个 HFile，合并过程中会完全清理掉被删除的数据、过期数据、超过版本设置的数据

Region 在 Compaction 的过程中会影响业务性能，而 Major Compaction 过程中所有的 HFile 都会参与合并，因此一般会关闭自动触发 Major Comapction，而是在业务低峰期手动触发。

HBase 通过 Compaction 操作提升了系统的读性能：

- Region 经过 Compaction 之后文件的数量减少，因而在读数据时的 IO 操作减少
- HFile 在合并的过程中会将其他 DataNode 的数据读取到当前 RegionServer 然后再合并，从而提高了数据的本地化率，减少读数据时的网络开销
- Major Compaction 会删除冗余的数据，减少了读数据时扫描的数据量

Compaction 过程需要读写数据，因此也会有明显的副作用：

- 执行文件合并之前需要读取数据，增加 IO 和网络开销，导致读数据时有明显的 “毛刺” 现象
- 文件合并之后需要再次写入磁盘，导致写放大

#### Compaction 时机

HBase 中 Compaction 操作通常会在三种情况下触发：

- MemStore flush 之后检查当前 Store 中的文件数，如果超过 `hbase.hstore.compactionThreshold` 的值就会触发 Compaction
- HRegionServer 在后台运行 `compactionChecker` 线程周期性的检查 Store 是否需要执行 Compaction
- 通过手动的方式触发 Major Compaction 执行

Region Compaction 触发后 HBase 就会开始执行 Compaction 流程，HRegionServer 在后台运行着 `compactSplit` 线程处理 Compaction，该线程首先会从对应 Store 中根据文件选取算法选择合适的 HFile 文件进行合并，然后将这些文件在特定的线程池中执行文件合并的具体操作。

#### Compaction 策略

Compaction 策略决定了合并哪些文件、如何合并这些文件以及如何生成合并之后的文件。

`Compaction` 首先会排除 Store 中不满足条件的 HFile 文件：

- 当前正在执行 Compaction 的 HFile 文件
- 文件大小超过 `hbase.hstore.compaction.max.size` 的文件

剩余的文件需要继续判断是否需要触发 Major Compaction，如果满足任意一个条件则这些文件需要全部参与 Compaction：

- 上次执行 Major Compaction 的时间早于 `hbase.hregion.majorcompaction` 并且文件数量小于 `hbase.hstore.compaction.max`
- 文件中含有 reference 文件(Region 分裂产生的临时文件)

如果不满足则会在候选文件上利用合并策略选择合适的文件进行合并，HBase 提供了多种文件和并策略：

- `RatioBasedCompactionPolicy`：选择
- `ExploringCompactionPolicy`
- `FIFOCompactionPolicy`
- `StripeCompactionPolicy`
- `DateTieredCompactionPolicy`

创建表的时候可以指定列簇的 Compaction 策略：

```java
ColumnFamilyDescriptorBuilder
    .setConfiguration(DefaultStoreEngine.DEFAULT_COMPACTOR_CLASS_KEY, FIFOCompactionPolicy.class.getName());
TableDescriptorBuilder.setCompactionEnabled(true)
    .setColumnFamily(columnFamily);
```

Compaction 合并小文件，一方面提高了数据的本地化率，降低了数据读取的响应延时，另一方面也会因为大量消耗系统资源带来不同程度的短时间读取响应毛刺。HBase 提供了 Compaction 接口，可以根据自己的应用场景以及数据集订制特定的 Compaction 策略，优化 Compaction 主要从几个方面进行：

- 减少参与 Compaction 的文件数，需要将文件根据 rowkey、version 或其他属性进行分割，再根据这些属性挑选部分重要的文件参与合并，并且尽量不要合并那些大文件
- 不要合并那些不需要合并的文件，比如基本不会被查询的老数据，不进行合并也不会影响查询性能
- 小 Region 更有利于 Compaction，大 Region 会生成大量文件，不利于 Compaction

##### FIFO Compaction

FIFO Compaction 策略参考了 RocksDB 的实现，它选择过期的数据文件，即该文件内的所有数据均已经过期，然后将收集到的文件删除，因此适用于此 Compaction 策略的对应列簇必须设置 TTL。

FIFO Compaction 策略适用于大量短时间存储的原始数据的场景，比如推荐业务、最近日志查询等。FIFO Compaction 可以在表或者列簇上设置：

```java

```

##### Tier-Based Compaction

在现实业务中，有很大比例的业务数据都存在明显的热点数据，最近写入的数据被访问的频率比较高，针对这种情况，HBase 提供了 Tire-Based Compaction。这种方案根据候选文件的新老程度将其分为不同的等级，每个等级都有对应的参数，比如 Compaction Ratio 表示该等级的文件的选择几率。通过引入时间等级和 Compaction Ratio 等概念，就可以更加灵活的调整 Compaction 效率。

Tire-Based Compaction 方案基于时间窗的概念，当时间窗内的文件数达到 threshold (可通过参数 min_threshold 配置)时触发 Compaction 操作，时间窗内的文件将会被合并为一个大文件。基于时间窗的 Compaction 可以通过调整窗口大小来调整优先级，但是没有一个窗口时间包括所有文件，因此这个方案没有 Major Compaction 的功能，只能借助于 TTL 来清理过期文件。

Tire-Based Compaction 策略适合下面的场景：

- 时间序列数据，默认使用 TTL 删除，同时不会执行 delete 操作（特别适合）
- 时间序列数据，有全局更新操作以及少部分删除操作（比较适合）

##### Level Compaction

Level Compaction 设计思路是将 Store 中的所有数据划分为很多层，每一层有一部分数据。

数据不再按照时间先后进行组织，而是按照 KeyRange 进行组织，每个 KeyRange 进行包含多个文件，这些文件所有数据的 Key 必须分布在同一个范围。

整个数据体系会被划分为很多层，最上层(Level 0)表示最新的数据，最下层(Level 6)表示最旧数据，每一层都由大量 KeyRange 块组成，KeyRnage 之间没有重合。层数越大，对应层的每个 KeyRange 块越大，下层 KeyRange 块大小是上一层的 10 倍，数据从 MemStore 中 flush 之后，会先落入 Level 0，此时落入 Level 0 的数据可能包含所有可能的 Key，此时如果需要执行 Compaction，只需要将 Level 0 中的 KV 逐个读取出来，然后按照 Key 的分布分别插入 Level 1 中对应的 KeyRange 块的文件中，如果此时刚好 Level 1 中的某个 KeyRnage 块大小超过了阈值，就会继续往下一层合并

Level Compaction 中依然存在 Major Compaction 的概念，发生 Major Compaction 只需要将部分 Range 块内的文件执行合并就可以，而不需要合并整个 Region 内的数据文件。

这种合并策略实现中，从上到下只需要部分文件参与，而不需要对所有文件执行 Compaction 操作。另外，对于很多“只读最近写入的数据”的业务来说，大部分读请求都会落到 Level 0，这样可以使用 SSD 作为上层 Level 存储介质，进一步优化读。但是 Level Compaction 因为层数太多导致合并的次数明显增多，对 IO 利用率并没有显著提升。

##### Stripe Compaction

Stripe Compaction 和 Level Compaction 原理相同，会将整个 Store 中的文件按照 key 划分为多个 Range，称为 stripe。stripe 的数量可以通过参数设定，相邻的 stripe 之间 key 不会重合。实际上从概念上看，stripe 类似于将一个大的 Region 划分成的小 Region。

随着数据写入，MemStore 执行 flush 形成 HFile，这些 HFile 并不会马上写入对应的 stripe，而是放到一个称为 L0 的地方，用户可以配置 L0 放置 HFile 的数量。一旦 L0 放置的文件数超过设定值，系统会将这些 HFile 写入对应的 stripe：首先读出 HFile 的 KV，再根据 key 定位到具体的 stripe，将 KV 插入对应的 stripe 文件中。stripe 就是一个小的 Region，因此在 stripe 内部依然会有 Minor Compaction 和 Major Compaction，stripe 由于数据量并不是很大，因此 Major Compaction 并不会消耗太多资源。另外，数据读取可以根据对应的 key 查找到对应的 stripe，然后在 stripe 内部执行查找，因为 stripe 的数据量相对较小，所以也会在一定程度上提升数据查找性能。

Stripe Compaction 有两种比较擅长的业务场景：

- 大 Region。小 Region 没有必要切分为 stripe，一旦切分反而会带来额外的管理开销，一般默认 Region 小于 2G，就不适合使用 Stripe Compaction
- Rowkey 具有统一格式，Stripe Compaction 要求所有数据按照 key 进行切分，生成多个 stripe，如果 rowkey 不具有统一格式，则无法切分





MemStore 的 flush 操作会逐步增加磁盘上的文件数目，合并(compaction)进程会将它们合并成规模更少但是更大的文件。合并有两种类型：Minor 合并和 Major 合并。

Minor 合并负责将一些小文件合并成更大的文件，合并的最小文件数由 ```hbase.hstore.compaction.min``` 属性设置，默认值为 3，同时该值必须大于等于 2。如果设置的更大一些则可以延迟 Minor 合并的发生，但同时在合并时需要更多的资源和更长的时间。Minor 合并的最大文件数由 ```hbase.store.compaction.max``` 参数设置，默认为 10。
可以通过设置 ```hbase.hstore.cmpaction.min.size``` 和 ```hbase.hstore.compaction.max.size``` 设置参与合并的文件的大小，在达到单次 compaction 允许的最大文件数之前小于最小阈值的文件也会参与 compaction 但是大于最大阈值的文件不会参与。

Major 合并将所有的文件合成一个，这个过程是通过执行合并检查自动确定的。当 MemStore 被 flush 到磁盘、执行 compaction 或者 major_compaction 命令、调用合并相关 API都会触发检查。

#### HFile 文件合并

选择出需要执行 Compaction 文件之后,`CompactSplit` 根据文件的大小选择合适的线程池来执行文件合并操作，`CompactSplit` 内部构造了多种线程池，不同的线程池处理不同大小的文件：

- `longCompactions`：处理总文件大小超过 `hbase.regionserver.thread.compaction.throttle` 的 Compaction 操作
- `shortCompactions`：处理文件大小不超过阈值的 Compaction 操作

确定执行 Compaction 操作的线程池之后就会在选定的线程池内执行合并操作，合并流程分为 4 步：

- 读出待合并 HFile 文件的 KeyValue，归并排序处理后写到 ./tmp 目录下的临时文件中
- 待合并的 HFile 处理万之后将临时文件移动到对应 Store 的数据目录
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

