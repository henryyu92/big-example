## Region

一个 Region 由一个或多个 Store 组成，Store 的个数取决于表中列簇(column family)的个数，每一个列簇对应一个 Store。在 HBase 中每个列簇的数据都集中存放在一起形成一个存储单元 Store，因此建议将具有相同 IO 特性的数据设置在同一个列簇中。

每一个 Store 由一个 MemStore 和一个或多个 HFile 组成，用户写入的数据会先写到 MemStore，当 MemStore 写满(缓存数据超过阈值，默认 128M)之后系统会异步地将数据 flush 成一个 HFile 文件，当 HFile 文件的数据超过一定阈值之后系统将会执行 Compact 操作将这些小文件通过一定策略合并成一个或多个大文件。


Region 中的数据是以 LSM 树的形式存放，LSM 树的索引分为两部分：内存部分和磁盘部分。在 Region 中内存部分就是 MemStore，而磁盘部分就是 StoreFile。

### MemoryStore

HBase 中一张表被水平切分成多个 Region，每个 Region 负责自己区域的数据读写请求，每个 Region 包含所有列簇数据。HBase 将不同列簇的数据存储在不同的 Store 中，每个 Store 由一个 MemStore 和一系列的 HFile 组成。

HBase 基于 LSM 树模型实现，所有的数据写入操作首先会顺序写入日志 HLog 再写入 MemStore，当 MemStore 中数据大小超过阈值之后再将这些数据批量写入磁盘，生成一个新的 HFile 文件。

MemStore 使用跳跃表实现，即 JDK 自带的数据结构 ConcurrentSkipListMap，保证数据写入、查找、删除等操作在 O(lgN) 的时间复杂度内完成，ConcurrentSkipListMap 使用 CAS 操作保证了线程安全。

MemStore 由两个 ConcurrentSkipListMap 实现，写入操作会将数据写入到其中一个 ConcurrentSkipListMap，当数据超过阈值之后创建一个新的 ConcurrentSkipListMap 用于接收数据，之前写满的ConcurrentSkipListMap 会执行 flush 操作落盘形成 HFile

#### MemStore 内存管理
MemStore 本质是一块缓存，每个 RegionServer 由多个 Region 构成，每个 Region 根据列簇的不同包含多个 MemStore，者写 MemStore 都是共享内存的，如果 Region 上对应的 MemStore 执行 flush 操作则会使得内存空间出现较多的碎片，触发 JVM 执行 Full GC 合并这些内存碎片

为了优化这种内存碎片可能导致的 Full GC，HBase 借鉴了线程本地分配缓存(Thread-Local Allocation Buffer, TLAB)的内存管理方式，通过顺序化分配内存、内存数据分块等特性使得内存碎片更加粗粒度，有效改善 Full GC，这种内存管理方式称为 MemStore 本地分配缓存(MemStore-Local Allocation Buffer, MSLAB)其具体步骤为：
- 每个 MemStore 会实例化得到一个 MemStoreLAB 对象，MemStoreLAB 会申请一个 2M 大小的 Chunk 数组，同时维护一个 Chunk 偏移量，该偏移量的初始值为 0
- 当一个 k-v 值插入 MemStore 后，MemStoreLAB 首先通过 KeyValue.getBuffer() 取得 data 数据，并将 data 数组复制到 Chunk 数组中，之后再将 Chunk 偏移量往前移动 data.length
- 当 Chunk 满了之后，再调用 new byte[2*1024*1024] 申请一个新的 Chunk

MSLAB 将内存碎片粗化为 2M 大小，使得 flush 之后残留的内存碎片粒度更粗，从而降低 Full GC 的触发频率。但是这种内存管理方式还存在一个问题，当 Chunk 写满之后需要创建一个新的 Chunk，新创建的 Chunk 会在新生代分配内存，如果 Chunk 申请频繁则会导致频繁的 YGC。MemStore Chunk Pool 将申请的 Chunk 循环利用而不是在 flush 之后释放掉从而避免频繁的创建新的 Chunk 降低 YGC 的频率，具体步骤为：
- 系统创建一个 Chunk Pool 来管理所有未被引用的 Chunk 而不是被 JVM 回收
- 如果一个 Chunk 没有再被引用则将其放入 Chunk Pool
- 如果当前 Chunk Pool 已经达到了容量最大值，就不会接收新的 Chunk
- 如果需要申请新的 Chunk 来存储 k-v 则首先从 Chunk Pool 中获取，如果能够获取则重复使用 Chunk，否则就重新申请一个新的 Chunk

HBase 中 MSLAB 功能默认是开启的，可以通过参数 ```hbase.hregion.memstore.mslab.chunksize``` 设置 ChunkSize 的大小，默认是 2M，建议保持默认值；Chunk Pool 功能默认是关闭的，通过参数 ```hbase.hregion.memstore.chunkpool.maxsize``` 为大于 0 的值才能开启，默认是 0，该值得取值范围为 [0,1] 表示整个 MemStore 分配给 Chunk Pool 的总大小；参数 ```hbase.hregion.memstore.chunkpool.initialsize``` 取值为 [0,1] 表示初始化时申请多少个 Chunk 放到 Chunk Pool 里面，默认是 0



### StoreFile