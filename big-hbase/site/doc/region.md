## Region

一个 Region 由一个或多个 Store 组成，Store 的个数取决于表中列簇(column family)的个数，每一个列簇对应一个 Store。在 HBase 中每个列簇的数据都集中存放在一起形成一个存储单元 Store，因此建议将具有相同 IO 特性的数据设置在同一个列簇中。

每一个 Store 由一个 MemStore 和一个或多个 HFile 组成，用户写入的数据会先写到 MemStore，当 MemStore 写满(缓存数据超过阈值，默认 128M)之后系统会异步地将数据 flush 成一个 HFile 文件，当 HFile 文件的数据超过一定阈值之后系统将会执行 Compact 操作将这些小文件通过一定策略合并成一个或多个大文件。


Region 中的数据是以 LSM 树的形式存放，LSM 树的索引分为两部分：内存部分和磁盘部分。在 Region 中内存部分就是 MemStore，而磁盘部分就是 StoreFile。

### MemoryStore


### StoreFile