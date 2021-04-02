## RegionServer 调优

### HBase 运维

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





#### Region 本地化