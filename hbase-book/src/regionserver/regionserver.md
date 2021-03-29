## RegionServer

RegionServer 是 HBase 系统中最核心的组件，由 HLog，BlockCache 和多个 Region 组成。`HRegionServer` 是 RegionServer 的实现，负责对外提供服务并且管理分布在上面的 Region。

`HRegionServer` 在启动时会在后台运行多个线程用于检测当前 RegionServer 上的各个组件：

- `compactSplitThread`：负责处理 Minor 合并，并且在合适时执行 Region 分裂
- `compactionChecker`：负责检查 Region 是否需要进行合并
- `periodicFlusher`：负责定期将 MemoStore 刷盘
- `movedRegionsCleaner`：负责清理已经移除的 Region



RegionServer 。其中 HLog 用来保证数据写入的可靠性；Region 是 HBase 中数据表的一个数据分片，一个 RegionServer 上通常会负责多个 Region 的数据读写；一个 Region 由多个 Store 组成，每个 Store 存放对应列簇的数据，每个 Store 包含一个 MemStore 和多个 HFile，数据写入时会先将对应列簇数据写入相应的 MemStore，一旦写入数据的内存大小超过阈值，MemStore 就会 fulsh 到磁盘形成 HFile 文件。HFile 文件存放在 HDFS 上，是一种定制化格式的数据存储文件，方便进行数据读取。
