## RegionServer

RegionServer 是 HBase 系统中最核心的组件，主要负责数据的写入、读取等基础操作。

RegionServer 由一个 HLog，一个 BlockCache 和多个 Region 组成。其中 HLog 用来保证数据写入的可靠性；BlockCache 可以将数据块缓存在内存中以提升数据读取性能；Region 是 HBase 中数据表的一个数据分片，一个 RegionServer 上通常会负责多个 Region 的数据读写；一个 Region 由多个 Store 组成，每个 Store 存放对应列簇的数据，每个 Store 包含一个 MemStore 和多个 HFile，数据写入时会先将对应列簇数据写入相应的 MemStore，一旦写入数据的内存大小超过阈值，MemStore 就会 fulsh 到磁盘形成 HFile 文件。HFile 文件存放在 HDFS 上，是一种定制化格式的数据存储文件，方便进行数据读取。



HRegionServer 是 RegionServer 的实现，负责对外提供服务并且管理 region



RegionServer 在后台启动的线程：

- CompactSplitThread: 负责检查 splits 并且执行 minor compaction
- MajorCompactionChecker: 负责检查 major compaction
- MemStoreFlusher: 周期性的将 MemStore 中的数据 flush 到 StoreFile 中
- LogRoller: 周期性的检查 RegionServer 的 WAL

