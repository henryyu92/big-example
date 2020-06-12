
## RegionServer
RegionServer 是 HBase 系统中最核心的组件，主要负责用户数据写入、读取等基础操作。RegionServer 包含多个模块：HLog、MemStore、HFile 以及 BlockCache。

RegionServer 是 HBase 系统响应读写请求的工作节点，一个 RegionServer 由一个或多个 HLog、一个 BlockCache 以及多个 Region 组成。其中 HLog 用来保证数据写入的可靠性；BlockCache 可以将数据块缓存在内存中以提升数据读取性能；Region 是 HBase 中数据表的一个数据分片，一个 RegionServer 上通常会负责多个 Region 的数据读写；一个 Region 由多个 Store 组成，每个 Store 存放对应列簇的数据，每个 Store 包含一个 MemStore 和多个 HFile，数据写入时会先将对应列簇数据写入相应的 MemStore，一旦写入数据的内存大小超过阈值，MemStore 就会 fulsh 到磁盘形成 HFile 文件。HFile 文件存放在 HDFS 上，是一种定制化格式的数据存储文件，方便进行数据读取。

## HBase 运维

HBCK(HBaseFsck) 工具可以检测 HBase 集群中 Region 的一致性和完整性，同时可以对损坏的集群进行修复。

使用 hbck 命令可以检查集群是否存在损坏：
```shell
./bin/hbase hbck
```
如果检查到 Region 不一致状态则会在窗口输出基本报告信息，使用 -detail 可以查看细节，同时该命令也可以指定表来指定检查范围。

HBCK 可以恢复集群不一致的问题，Region 一致性问题修复有两个基本选项：
- fixAssignments：修复 assign 相关问题，如没有 assign、assign 不正确或者同时 assign 到多台 RegionServer 上
- fixMeta：主要修复 .regioninfo 文件和 hbase:meta 元数据表的不一致，修复原则是以 HDFS 文件为准

### HBase 核心参数配置
- hbase.hregion.max.filesize：默认为 10G，表示 Region 中最大的 Store 中所有文件大小阈值，一旦超过这个值整个 Region 就会执行分裂。太大会导致在执行 compaction 的时候消耗大量系统资源，太小导致 Region 分裂频繁，过多 Region 在故障恢复时比较耗时
- hfile.block.cache.size：默认 0.4，用于设置 LRUBlockCache 的内存大小占 JVM 内存的比例
- hbase.bucketcache.ioengine：BucketCache 策略模式选择，可选项有 heap, offheap 和 file 三种，分别表示使用堆内存、堆外内存和 SSD 作为缓存存储介质
- hbase.bucketcache.size：堆外内存大小，依赖于物理内存大小
- hbase.hregion.memstore.flush.size：默认为 128M，大于该阈值 MemStore 就会 flush，阈值过小则会频繁 flush 造成小文件过多，调大阈值会导致宕机恢复时需要 split 的 HLog 数量变多从而延长故障恢复时间
- hbase.hregion.memstore.block.multiplier：默认为 4，表示一旦某 Region 中所有写入 MemStore 的数据大小总和达到或超过阈值 hbase.hregion.memstore.block.multiplier * hbase.hregion.memstore.flush.size 就会触发 flush 操作
- hbae.regionserver.global.memstore.size：默认为 0.4，表示占用总 JVM 内存大小的 40%，整个 RegionServer 上所有写入 MemStore 的数据大小总和超过这个阈值就会强制执行 flush 操作


### HBase 表设计
生产线创建表时不要单独使用表名，而应该使用命名空间加表名的形式，同一业务的相关表放在同一个命名空间下，不同业务使用不同的命名空间

列簇可以根据业务需要配置不同的属性：
- VERSIONS：系统保留的最大版本，默认为 1，数据的版本一旦超过该值老的版本就会被删除
- BLOCKCACHE：是否开启 BlockCache，默认为 true，表示数据 Block 从 HFile 加载出来之后会被放入读缓存；如果设置为 false，则读取的数据 Block 不会放入读缓存，在数据量很大且读取没有任何热点或者表中的数据仅供 OLAP 分析而没有 OLTP 业务时可以设置为 false
- BLOOMFILTER：指定布隆过滤器的类型，可选项有 NONE、ROW 和 ROWCOL，默认为 ROW。ROW 根据 rowkey 判断待查找的数据是否存在于 HFile，而 ROWCOL 模式只对指定列的随机读有优化作用
- TTL：设置数据失效时间，数据自动失效可以减少数据规模，提升查询性能。TTL 过期的数据是通过 Compaction 机制进行删除的
- COMPRESSION：指定数据压缩算法，可选项有 NONE、SNAPPY、LZ4 和 ZLTD。数据压缩可以减少数据量，进而减少 IO，但是数据的压缩和解压缩会消耗 CPU 资源。HBase 的数据压缩是 Block 级别的，生产线上推荐使用 SNAPPY 压缩算法
- DATA_BLOCK_ENCODING：指定数据编码算法，可选项有 NONE、PREFIX、DIFF、FASTDIFF 和 PREFIX_TREE，数据编码同样可以减少数据量，也同样会消耗 CPU 资源
- BLOCKSIZE：指定文件块大小，默认 64K。Block 是 HBase 存储的最小粒度，读数据时会先读出整个 Block，然后再遍历出需要的数据，在 get 请求较多时可以调小 BLOCKSIZE，如果 scan 请求较多时可以调大 BLOCKSIZE
- DFS_REPLICATION：数据 Block 在 HDFS 上存储的副本数，默认和 HDFS 文件系统设置相同。DFS_REPLICATION 可以让不同的列簇数据在 HDFS 上拥有不同的副本数，对于某些列簇数据量较大但是数据不重要可以调低
- IN_MEMORY：如果表中某些列的数据量不大，但是进行 get 和 scan 的频率特别高，使用 IN_MEMORY 可以使得 get 和 scan 延迟更低

在创建表时也可以设置表级的属性：
- 预分区：预分区在 HBase 中非常重要，不经过预分区的业务表在后期会出现数据分布极度不均衡的情况，从而造成读写请求不均衡。与预分区有关的两个属性为 NUMREGIONS 和 SPLITALGO，其中 NUMREGIONS 表示预分区的个数，该属性由数据量、Region 大小等因素决定；SPLITALGO 表示切分策略，可选项有 UniformSplit 和 HexStringSplit 两种，HexStringSplit 适用于 rowkey 前缀时十六进制字符串场景，UniformSplit 适用于 rowkey 时随机字节数组的场景，也可以通过实现 RegionSplitter.SplitAlgorithm 接口自定义切分策略
- MAX_FILESIZE：最大文件大小，功能与配置文件中 "hbase.hregion.max.filesize" 的配置相同，默认为 10G，主要影响 Region 切分的时机
- READONLY：设置表只读，默认 false
- COMPACTION_ENABLED：设置是否开启 Compaction，默认为 true，表示允许 Minor/Major Compaction 自动执行
- MEMSTORE_FLUSHSIZE：单个 MemStore 大小，与 hbase.hregion.memstore.flush.size 配置相同，默认 128M
- DURABLITY：WAL 持久化等级，默认为 USER_DEFAULT，可选项有 SKIP_WAL、ASYNC_WAL 以及 FSYNC_WAL，在允许异常情况下部分数据丢失时可以设置为 SKIP_WAL 或者 ASYNC_WAL

### Procedure 框架
一个 Procedure 由多个 subtask 组成，每个 subtask 是一些执行步骤的集合，这些执行步骤中又依赖部分 Procedure。Procedure 提供了两个方法：execute() 和 rollback()，用于实现 Procedure 的执行逻辑和回滚逻辑，这两个方法的实现需要保证幂等性。

以建表的 Procedure 为例，讨论 Procedure V2 的原子性执行和回滚：
- Procedure 引入了 Procedure Store 的概念，Procedure 内部的任何状态变化，或者 Procedure 的子 Procedure 状态变化，或者从一个 subtask 转移到另一个 subtask 都会被持久化到 HDFS 中，持久化的方式是在 Master 的内存中维护一个实时的 Procedure 镜像，然后有任何更新都把更新顺序写入 Procedure WAL 日志中
- Procedure 引入了回滚栈和调度队列的概念，回滚栈是一个记录 Procedure 的执行过程的栈结构，当需要回滚的时候则依次出栈执行过程进行回滚；调度队列是 Procedure 调度执行时的双向队列 

### In Memory Compaction
HBase 2.x 版本引入了 Segment 的概念，本质上是一个维护一个有序的 cell 列表，根据 cell 列表是否可更改，Segment 可以分为两种类型：
- MutableSegment：支持添加 cell、删除 cell、扫描 cell、读取某个 cell 等操作，一般使用 ConcurrentSkipListMap 来维护
- ImmutableSegment：只支持扫描 cell 和读取某个 cell 这种查找类操作，不支持添加、删除等写入操作，只需要一个数据维护即可

HBase 还引入了 CompactingMemStore，将原来的 128M 大小的 MemStore 划分成很多个小的 Segment，其中一个 MutableSegment 和多个 ImmutableSegment。Column Family的写入操作时，先写入 MutableSegment，一旦发现 MutableSegment 占用的空间超过 2 MB，则把当前 MutableSegment 切换成 ImmutableSegment，然后初始化一个新的 MutableSegment 供后续的写入。

CompactingMemStore 中的所有 ImmutableSegment 称为 pipeline，本质上是按照 ImmutableSegment 加入的顺序组成的一个 FIFO 队列。当 Column Family 发起读取或者扫描操作时，需要将这个 CompactingMemStore 的一个 MutableSegment、多个 ImmutableSegment 以及磁盘上的多个 HFile 组织成多个内部数据有序的 Scanner，然后将这些 Scanner 通过多路归并算法合并生成可以读取 Column Family 数据的 Scanner。

随着数据的不断写入，ImmutableSegment 个数不断增加，需要多路归并的 Scanner 就会很多，降低读取操作的性能。所以当 ImmutableSegment 个数达到某个阈值(hbase.hregion.compacting.pipeline.segments.limit 设置，默认值 2)时，CompactingMemStore 会触发一次 InMemtory 的 MemStoreCompaction，也就是将 CompactingMemStore 的所有 ImmutableSegment 多路归并成一个 ImmutableSegment，这样 CompactingMemStore 产生的 Scanner 数量就会得到很好地控制，对杜兴能基本无影响。

ImmutableSegment 的 Compaction 同样会清理掉无效的数据，包括 TTL 过期数据、超过指定版本的数据、以及标记为删除的数据，从而节省了内存空间，使得 MemStore 占用的内存增长变缓，减少 MemStore Flush 的频率。

CompactingMemStore 中引入 ImmutableSegment 之后使得更多的性能优化和内存优化得到可能。ImutableSegment 需要维护的有序列表不可变，因此可以直接使用数组而不再需要使用跳跃表(ConcurrentSkipListMap)，从而节省了大量的节点开销，也避免了内存碎片。

相比 DefaultMemStore，CompactingMemStore 触发 Flush 的频率会小很多，单次 Flush 操作生成的 HFile 数据量会变大，因此 HFile 数量的增长就会变慢，这样至少有三个好处：
- 磁盘上 Compaction 的触发频率降低，HFile 数量少了，无论是 Minor Compaction 还是 Major Compaction 次数都会降低，这会节省很大一部分磁盘带宽和网络带宽
- 生成的 HFile 数量变少，读取性能得到提升
- 新写入的数据在内存中保留的时间更长，对于写完立即读的场景性能会有很大提升

使用数组代替跳跃表后，每个 ImmutableSegment 仍然需要在内存中维护一个 cell 列表，其中每一个 cell 指向 MemStoreLAB 中的某一个 Chunk。可以把这个 cell 列表顺序编码在很少的几个 Chunk 中，这样 ImmutableSegment 的内存占用可以进一步减少，同时实现了零散对象“凑零为整”，进一步减少了内存的占用。

可以在配置文件 hbase-site.xml 中配置集群中所有表都开启 In Memory Compaction 功能：
```xml
hbae.hregion.compacting.memstore.type=BASIC
```
也可以在创建表的时候指定开启 In Memory Compaction 功能：
```shell
create 'test', {NAME=>'cf', IN_MEMORY_COMPACTION=>'BASIC'}
```
In Memory Compaction 有三种配置值：
- NONE：默认值，表示不开启 In Memory Compaction 功能，而使用 DefaultMemStore
- BASIC：开启 In Memory Compaction 功能，但是在 ImmutableSegment 上 Compaction 的时候不会使用 ScanQueryMatcher 过滤无效数据，同时 cell 指向的内存数据不会发生任何移动
- EAGER：开启 In Memory Compaction 功能，在 ImmutableSegment Compaction 的时候会通过 ScanQueryMatcher 过滤无效数据，并重新整理 cell 指向的内存数据，将其拷贝到一个全新的内存区域

如果表中存在大流量特定行的数据更新，则使用 EAGER，否则使用 BASIC。

### MOB 对象存储

### HBase 社区流程
- 创建 isuue 描述相关背景：地址为 https://issues.apache.org/jira/browse/HBASE  初次使用需要发邮件给 dev@hbase.apache.org，开通 Jira 的 Contributor 权限
- 将 issue assign 给自己
- 将代码修改导出 patch，并 attach 到 issue 上，代码提交到本地 git 仓库后，通过 git format -l 将最近一次提交的代码导出到 patch 文件中，并将文件命名为 <issue-id>.<branch>.v1.patch，然后可以在 github 提交 pull request
- 将 issue 状态更改为 path available
- HadoopQA 自动为新提交的 Patch 跑所有 UT
- 待 UT 通过后，请求 Commiter/PMC 代码 review
- 完成代码修改，并获得 Commiter/PMC 的 +1 认可
- 注意编写 issue 的 Release Note 内容，告诉未来使用新版本 HBase 的用户，本次修改主要对用户有何影响
- commiter/PMC 将代码提交到官方 git
- 将 issue 状态设为 Resolved




### 版本(Versions)
行和列键表示为字节，而版本则使用长整数指定；HBase 的数据是按照 vesion 降序存储的，所以在读取数据的时候读到的都是最新版本的数据。

列存储的最大版本数在创建表的时候就需要指定，在 HBase 0.96 之前默认最大版本数为 3，之后更改为 1。可以通过 alter 命令或者 HColumnDescriptor.DEFAULT_VERSIONS 来修改。
```shell
# 设置 t1 表的 f1 列族的最大存储版本为 5
alter 't1', Name=>'f1', VERSIONS=>5
```
也可以指定列族的最小存储版本，默认是 0 即该功能不启用：
```shell
# 设置 t1 表的 f1 列族的最小存储版本为 2
alter 't1', NAME=>'f1', MIN_VERSIONS=>2
```
### HBase Schema
- Region 的大小控制在 10-50 GB
- Cell 的大小不要超过 10M，如果有比较大的数据块建议将数据存放在 HDFS 而将引用存放在 HBase
- 通常每个表有 1 到 3 个列族
- 一个表有 1-2 个列族和 50-100 个 Region 是比较好的
- 列族的名字越短越好，因为列族的名字会和每个值存放在一起

#### hbase:meta
hbase:meta 和其他 HBase 表一样但是不能在 Hbase shell 通过 list 命令展示。hbase:meta表（以前称为.meta.）保存系统中所有 Region 的列表，hbase:meta 表的存放地址保存在 zookeeper 上，需要通过 zookeeper 寻址。

hbase:meta 存放着系统中所有的 region 的信息，以 key-value 的形式存放：
- key 以 ([table],[region start key],[region id]) 表示的 Region，如果 start key 为空的则说明是第一个 Region，如果 start key 和 end key 都为空则说明只有一个 Region
- value 保存 region 的信息，其中 info:regioninfo 是当前 Regsion 的 HRegionInfo 实例的序列化；info:server 是当前 Region 所在的 RegionServer 地址，以 server:port 表示；info:serverstartcode 是当前 Region 所在的 RegionServer 进程的启动时间

当 HBase 表正在分裂时，info:splitA 和 info:splitB 分别代表 region 的两个孩子，这些信息也会序列化到 HRegionInfo 中，当 region 分裂完成就会删除。

### Ref
- [HTAP(Hybrid Transaction and Analytical Processing)]()
- [空间局部性]()
- [时间局部性]()