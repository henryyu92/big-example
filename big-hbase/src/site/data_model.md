
## RegionServer
RegionServer 是 HBase 系统中最核心的组件，主要负责用户数据写入、读取等基础操作。RegionServer 包含多个模块：HLog、MemStore、HFile 以及 BlockCache。

RegionServer 是 HBase 系统响应读写请求的工作节点，一个 RegionServer 由一个或多个 HLog、一个 BlockCache 以及多个 Region 组成。其中 HLog 用来保证数据写入的可靠性；BlockCache 可以将数据块缓存在内存中以提升数据读取性能；Region 是 HBase 中数据表的一个数据分片，一个 RegionServer 上通常会负责多个 Region 的数据读写；一个 Region 由多个 Store 组成，每个 Store 存放对应列簇的数据，每个 Store 包含一个 MemStore 和多个 HFile，数据写入时会先将对应列簇数据写入相应的 MemStore，一旦写入数据的内存大小超过阈值，MemStore 就会 fulsh 到磁盘形成 HFile 文件。HFile 文件存放在 HDFS 上，是一种定制化格式的数据存储文件，方便进行数据读取。

### HLog
HBase 中系统故障恢复以及主从复制都是基于 HLog 实现。默认情况下，所有的写入操作(增加、更新和删除)的数据都先以追加形式写入 HLog，然后再写入 MemStore，当 RegionServer 异常导致 MemStore 中的数据没有 flush 到磁盘，此时需要回放 HLog 保证数据不丢失。此外，HBase 主从复制需要主集群将 HLog 日志发送给从集群，从集群在本地执行回放操作，完成集群之间的数据复制。

每个 RegionServer 默认拥有一个 HLog，1.1 版本后可以开启 MultiWAL 功能允许多个 HLog，每个 HLog 是多个 Region 共享的。HLog 中，日志单元 WALEntry 表示一次行级更新的最小追加单元，由 HLogKey 和 WALEdit 两部分组成，其中 HLogKey 由 tableName, regionName 以及 sequenceId 组成

HBase 中所有数据都存储在 HDFS 的指定目录(默认 /hbase)下，可以通过 hadoop 命令查看目录下与 HLog 有关的子目录：
```shell
hdfs dfs get /hbase
```
HLog 文件存储在 WALs 子目录下表示当前未过期的日志，同级子目录 oldWALs 表示已经过期的日志，WALs 子目录下通常有多个子目录，每个子目录代表一个 RegionServer，目录名称为 ```<domain>,<port>,<timestamp>```，子目录下存储对应 RegionServer 的所有 HLog 文件，通过 HBase 提供的 hlog 命令可以查看 HLog 中的内容：
```shell
./hbase hlog
```

HLog 文件生成之后并不会永久存储在系统中，HLog 整个生命周期包含 4 个阶段：
- HLog 构建：HBase 的任何写操作都会先将记录追加写入到 HLog 文件中
- HLog 滚动：HBase 后台启动一个线程，每隔一段时间(参数 ```hbase.regionserver.logroll.period``` 设置，默认 1 小时)进行日志滚动，日志滚动会新建一个新的日志文件，接收新的日志数据
- HLog 失效：写入数据一旦从 MemSotre 落盘到 HDFS 对应的日志数据就会失效。HBase 中日志失效删除总是以文件为单位执行，查看 HLog 文件是否失效只需要确认该 HLog 文件中所有日志记录对应的数据是否已经完成落盘，如果日志中所有记录已经落盘则可以认为该日志文件失效。一旦日志文件失效，就会从 WALs 文件夹移动到 oldWALs 文件夹，此时 HLog 文件并未删除
- HLog 删除：Master 后台会启动一个线程，每隔一段时间(参数 ```hbase.master.cleaner.interval``` 设置，默认 1 分钟)减产一次文件夹 oldWALs 下的所有失效日志文件，确认可以删除之后执行删除操作。确认失效可以删除由两个条件：
  - HLog 文件没有参与主从复制
  - HLog 文件在 oldWALs 文件夹中存在时间超过 ```hbase.master.logcleaner.ttl``` 设置的时长(默认 10 分钟)

### MemStore
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

### BlockCache
为了提升读取性能，HBase 实现了一种读缓存结构 BlockCache，客户端在读取某个 Block 时首先检查 BlockCache 中是否存在该 Block，如果存在则直接从 BlockCache 中读取，否则从 HFile 中加载数据并将加载的 Block 放入 BlockCache

Block 是 HBase 中最小的数据读取单元，即数据从 HFile 中读取都是以 Block 为最小单元执行的。BlockCache 是 RegionServer 级别的，一个 RegionServer 只有一个 BlockCache 的初始化工作，HBase 实现了三种 BlockCache 方案：LURBlockCache、SlabCache、BucketCache。LRUBlockCache 是将所有数据都放入 JVM 堆中，SlabCache 和 BucketCache 允许将部分数据存储在堆外，这是因为 JVM 垃圾回收机制经常导致程序较长时间暂停，而采用堆外内存对数据进行管理可以有效缓解系统长时间 GC

LRUBlockCache 是 HBase 目前默认的 BlockCache 机制，使用 ConcurrentHashMap 管理 BlockKey 到 Block 的映射关系，查询时只需要根据 BlockKey 从 HashMap 中获取即可。该方案采用严格的 LRU 淘汰算法，当 Block Cache 总量达到一定阈值之后就会自动启动淘汰机制，最近最少使用的 Block 会被置换出来。

HBase 的 LRUBlockCache 采用了缓存分层设计，将整个 BlockCache 分为三部分：single-access、multi-access 和 inmemory，分别占到整个 BlockCache 大小的 25%、50%、25%。在一次随机读中，一个 Block 从 HDFS 中加载出来之后首先放入 single-access 区，后续如果有多次请求访问到这个 Block，就会将这个 Block 移到 multi-access 区，而 in-memory 区表示数据可以常驻内存。

in-memory 区用于存放访问频繁、量小的数据，比如元数据。在建表的时候设置列簇属性 IN_MEMORY=true 之后该列簇的 Block 在磁盘中加载出来后会直接放入 in-memory 区，但是数据写入时并不会写入 in-memory 区，而是和其他 BlockCache 区一样，只有在加载的时候才会放入，进入 in-memory 区的 Block 并不意味着一直存在于该区域，在空间不足的情况下依然会基于 LRU 淘汰算法淘汰最近不活跃的一些 Block

HBase 元数据(hbase:meta, hbase:namespace 等)都存放在 in-memory 区，因此对于很多业务表来说，设置数据属性 IN_MEMEORY=ture 时需要注意可能会由于空间不足而导致 hbase:meta 等元数据被淘汰，从而会严重影响业务性能

系统将 BlockKey 和 Block 放入 HashMap 后都会检查是否达到阈值，如果达到阈值就会唤醒淘汰线程对 Map 中的 Block 进行淘汰，系统设置了 3 个 MinMaxPriorityQueue 分别对应 3 个分层，每个队列中的元素按照最近最少被使用的规则排列，然后释放最近最少使用的 Block 的内存。

LRUBlockCache 方案使用 JVM 提供的 HashMap 管理缓存，随着数据从 single-access 区晋升到 multi-access 区或长时间停留在 single-access 区，对应的内存对象会从 young 区晋升到 old 区，此时 Block 被淘汰后变成垃圾需要被回收，而 CMS 垃圾回收算法会产生大量内存碎片导致 Full GC

#### SlabCache
SlabCache 使用 Java NIO DirectByteBuffer 技术实现堆外内存存储，系统在初始化时会分配两个缓存区，分别占整个 BlockCache 大小的 80% 和 20%，每个缓存区分别存储固定大小的 Block，其中前者主要存储大小等于 64K 的 Block，后者存储大小等于 128K 的 Block，如果一个 Block 太大会导致两个缓存区都无法缓存。

和 LRUBlockCache 一样，SlabCache 也是使用 Least-Recently-Used 算法淘汰过期的 Block，和 LRUBlockCache 不同的是 SlabCache 淘汰 Block 时只需要将对应的 BufferByte 标记为空闲，后续 cache 对其上的内存直接进行覆盖即可

#### BucketCache
BucketCache 通过不同配置方式可以工作在三种模式下：
- heap：表示 Bucket 是从 JVM Heap 中申请的
- offheap：使用 DirectByteBuffer 技术实现堆外内存存储管理
- file：使用 SSD 存储介质来缓存 Data Block

无论在哪种模式下，BucketCache 都会申请许多带有固定大小标签的 Bucket。和 SlabCache 一样，一种 Bucket 存储一种指定 BlockSize 的 Data Block，但和 SlabCache 不同的是 BucketCache 会在初始化的时候申请 14 种不同大小的 Bucket，而且如果某一种 Bucket 空间不足，系统会自动从其他 Bucket 空间借用内存使用，因此不会出现内存使用率低的情况。

在实际中，HBase 将 BucketCache 和 LRUBlockCache 组合使用，称为 CombinedBlockCache。系统在 LRUBlockCache 中主要存储 Index Block 和 Bloom Block，而将 Data Block 存储在 BucketCache 中，因此一次随机读需要现在 LRUBlockCache 中查找到对应的 IndexBlock，然后再到 BucketCache 查找对应的 DataBlock

[HBASE-11425]()

BucketCache 没有使用 JVM 内存管理算法来管理缓存，因此大大降低了因为出现大量内存碎片导致 Full GC 发生的风险。

HBase 启动之后会在内存中申请大量的 Bucket，每个 Bucket 的大小默认为 2 MB，每个 Bucket 会有一个 baseoffset 变量和一个 size 变量，其中 baseoffset 变量表示这个 Bucket 在实际的物理空间中的起始地址，因此 Block 的物理地址就可以通过 baseoffset 和该 Block 在 Bucket 的偏移量唯一确定；size 变量表示这个 Bucket 可以存放的 Block 大小。

HBase 使用 BucketAllocator 类实现对 Bucket 的组织管理：
- HBase 会根据每个 Bucket 的 size 标签对 Bucket 进行分类，相同 size 标签的 Bucket 由同一个 BucketSizeInfo 管理
- HBase 在启动时就决定了 size 标签的分类，默认标签有 (4+1)K、(8+1)K、(16+1)K、...、(512+1)K。系统会首先从小到大遍历一次所有 size 标签，为每种 size 标签分配一个 Bucket，最后所有剩余的 Bucket 都分配最大的 size 标签
- Bucket 的 size 标签可以动态调整，当某种 size 标签的 Bucket 用完之后其他空闲的 Bucket 就可以转换成为对应 size 的 Bucket，但是会至少保留一个该 size 的 Bucket

BucketCache 中 Block 写入缓存以及从缓存中读取 Block 的流程主要包括 5 个模块：
- RAMCache：存储 blockKey 和 Block 对应关系的 HashMap
- WriteThread：负责异步地将 Block 写入到内存空间
- BucketAllocator：实现对 Bucket 的组织管理，为 Block 分配内存空间
- IOEngine：具体的内存管理模块，将 Block 数据写入对应地址的内存空间
- BackingMap：用来存储 blockKey 与对应物理内存偏移量的映射关系，并且根据 blockKey 定位具体的 Block

Block 缓存的写入流程：
- 将 Block 写入 RAMCache，在实际实现中，HBase 设置了多个 RAMCache，系统首先会根据 blockKey 进行 hash，根据 hash 结果将 Block 分配到对应的 RAMCache 中
- WriteThread 从 RAMCache 中取出所有的 Block。HBase 会同时启动多个 WriteThread 并发地执行异步写入，每个 WriteThread 对应一个 RAMCache
- 每个 WriteThread 会遍历 RAMCache 中所有 Block，分别调用 bucketAllocator 为这些 Block 分配内存空间
- BucketAllocator 会选择与 Block 大小对应的 Bucket 进行存放，并且返回对应的物理地址偏移量 offset
- WriteThread 将 Block 以及分配好的物理地址偏移量传给 IOEngine 模块，执行具体的内存写入操作
- 写入成功后，将 blockKey 与对应物理内存偏移量的映射关系写入 BackingMap 中，方便后续查找时根据 blockKey 直接定位

Block 缓存读取流程：
- 首先从 RAMCache 中查找，对于还没有来得及写入 Bucket 的缓存 Block，一定存储在 RAMCache 中
- 如果在 RAMCache 中没有找到，再根据 blockKey 在 BackingMap 中找到对应的物理偏移地址量 offset
- 根据物理偏移地址 offset 直接从内存中查找对应的 Block 数据

思考：
- 在 BucketCache 缓存一个 Block 时，设计为先缓存 Block 到 RAMCache，然后再异步写入 IOEngine 的好处
- 单独设计一个 backingMap 存放每个 Block 的 (offset, length) 而不是直接把 Block 存放到 backingMap 中的原因
- Block 写入流程中，如果 Block 写入 IOEngine 时异常失败，是否存在 offheap 内存泄漏的问题？HBase 如何解决

BucketCache 默认有三种工作模式：heap、offheap 和 file。这三种工作模式在内存逻辑组织形式以及缓存流程上是相同的，但是三者对应的最终存储介质有所不同，即 IOEngine 有所不同。

heap 模式和 offheap 模式都使用内存作为最终存储介质，内存分配查询也都使用 Java NIO ByteBuffer 技术。二者不同的是 heap 模式分配内存会调用 ByteBuffer.allocate() 方法从 JVM 提供的 heap 区分配，而 offheap 模式会调用 ByteBuffer.allocateDirect() 方法直接从操作系统分配。相比 heap 模式，offheap 模式因为内存属于操作系统，所以降低了因为内存碎片导致 Full GC 的风险；但是 offheap 在存储数据时需要从 JVM 拷贝到系统内存，读取数据时需要从系统内存读取到 JVM 中从而影响性能。

file 模式使用 Fussion-IO 或者 SSD 作为存储介质，可以提供更大的存储容量

BucketCache 配置在使用不同的模式时需要不同的配置参数，heap 模式配置如下：
```xml
<property>
    <name>hbase.bucketcache.ioengine</name>
    <value>heap</value>
</property>
<!-- 设置 bucketcache 占用内存的百分比或者直接设置 bucketcache 大小 -->
<property>
    <name>hbase.bucketcache.size</name>
    <value>0.4</value>
</property>
```
offheap 模式的配置：
```xml
<property>
    <name>hbase.bucketcache.ioengine</name>
    <value>offheap</value>
</property>
<!-- 设置 bucketcache 占用内存的百分比或者直接设置 bucketcache 大小 -->
<property>
    <name>hbase.bucketcache.size</name>
    <value>0.4</value>
</property>
```
file 模式的配置：
```xml
<property>
    <name>hbase.bucketcache.ioengine</name>
    <value>file</value>
</property>
<!-- 设置 bucketcache 占用内存的百分比或者直接设置 bucketcache 大小 -->
<property>
    <name>hbase.bucketcache.size</name>
    <value>10 * 1024</value>
</property>
<property>
    <name>hbase.bucketcache.persistent.path</name>
    <value>file:/cache_path</value>
</property>
```
## HBase 读写流程
### HBase 写入流程
HBase 采用 LSM 树架构，适用于写多读少的应用场景。HBase 没有提供 update, delete 接口，HBase 中对数据的更新、删除也被认为是一个写入操作，更新操作会写入一个最新版本的数据，删除操作会写入一条标记为 delted 的数据。

HBase 的写入流程分为三个阶段：
- 客户端处理阶段：客户端将写请求预处理，根据集群元数据定位写入数据所在的 RegionServer，并将写请求发送给对应的 RegionServer
- RegionServer 写入阶段：RegionServer 接收到写入请求之后将数据解析写入 WAL 和对应 Region 列簇的 MemStore，并返回成功
- MemStore Flush 阶段：当 Region 中 MemStore 容量超过一定阈值，系统会异步执行 flush 操作将内存中的数据写入文件形成 HFile

HBase 客户端处理写入请求核心流程分为三步：
- 用户提交 put 请求后，HBase 客户端会将写入的数据添加到本地缓冲区中，当本地缓冲区大小超过阈值(默认设置为 2M)后会通过 AsyncProcess 异步批量提交。当 HBase 配置 autoflush = true 时会直接将 put 请求发送给 RegionServer 处理，默认为 true
- 在提交请求之前，HBase 会在元数据表 hbase:meta 中根据 rowkey 找到数据归属的 RegionServer，定位通常是通过 HConnection.locateRegion 方法完成。如果是批量请求，会将 rowkey 按照 RegionLocation 分组，不同的分组发送到不同的 RegionServer，每个分组对应一次 RPC 请求
- HBase 为每个 HRegionLocation 构造一个远程 RPC 请求 MultiServerCallable 并通过 rpcCallerFactory.newCaller 执行调用，将请求经过 Protobuf 序列化后发送给对应的 RegionServer

HBase client 和 ZooKeeper 以及 RegionServer 交互：
- 客户端根据写入的表以及 rowkey 在元数据缓存中查找，如果能查找到该 rowkey 所在的 RegionServer 以及 Region，则直接发送请求到目标 RegionServer
- 如果客户端缓存没有查找到对应的 rowkey 信息，则需要到 ZooKeeper 的 /hbase-root/meta-region-server 节点查找 HBase 元数据表所在的 RegionServer，然后向 hbase:meta 所在的 RegionServer 发送查询请求，在元数据表中查找 rowkey 所在的 RegionServer 以及 Region 信息。客户端收到返回的结果后缓存到本地，以备下次使用
- 客户端根据 rowkey 相关元数据信息将写入请求发送给目标 RegionServer，RegionServer 接收到请求之后会解析出具体的 Region 信息，查到对应的 Region 对象，并将数据写入目标 Region 的 MemStore 中

RegionServer 接收到写入请求之后，首先反序列化为 put 对象，然后执行各种检查操作，比如 Region 是否只读、MemStore 大小是否超过 blockingMemstoreSize 等。检查完成之后会执行一些核心操作：
- Acquire locks：HBase 中使用行锁保证对同一行数据的更新都是互斥操作，用以保存更新的原子性
- Update LATEST_TIME STAMP：更新所有待写入 KeyValue 的时间戳为当前系统时间
- Build WAL edit：HBase 使用 WAL 机制保证数据可靠性，写入操作中所有 KeyValue 会构建成一条 WALEdit 记录
- Append WALEdit To WAL：WALEdit 记录顺序写入 HLog 中，此时不需要执行 sync 操作，内部使用 disruptor 实现高效的生产者消费者队列来实现 WAL 的追加写入操作
- Write back to MemStore：写入 WAL 之后再将数据写入 MemStore
- Relase row locks：释放行锁
- Sync wal：HLog 真正 sync 到 HDFS，在释放行锁之后执行 sync 操作是为了尽量减少持锁时间，提升写性能。如果 sync 失败，执行回滚操作将 MemStore 中已经写入的数据移除
- 结束写事务：此时该线程的更新操作才会对其他读请求可见，更新才实际生效

随着数据的不断写入，MemStore 中存储的数据会越来越多，系统为了将使用的内存保持在一个合理的水平，会将 MemStore 中的数据写入文件形成 HFile。flush 阶段是 HBase 非常核心的阶段，需要重点关注三个问题：
- MemStore Flush 的触发时机
- MemStore flush 的整体流程
- HFile 的构建过程

HBase 中 HLog 保证成功写入 MemStore 中的数据不会因为进程异常退出或者机器宕机而丢失，HBase 定义了多个 HLog 持久化等级，使得可以在数据高可靠和写入性能之间进行权衡

HBase 可以通过设置 HLog 的持久化等级决定是否开启 HLog 机制以及 HLog 的落盘方式，HLog 的持久化等级分为五个等级：
- SKIP_WAL：只写缓存，不写 HLog 日志，这种方式可以极大地提升写入性能单数句有丢失的风险
- ASYNC_WAL：异步将数据写入 HLog 日志中
- SYNC_WAL：同步将数据写入日志文件中，并没有真正落盘
- FSYNC_WAL：同步将数据写入日志文件并强制落盘，可以保证数据不丢失，但是性能相对比较差
- USER_DEFAULT：默认 HBase 使用 SYNC_WAL 等级持久化

可以通过客户端设置 HLog 持久化等级：
```java
put.setDurability(Durability.SYNC_WAL);
```

HBase 使用 LMAX Disruptor 框架实现了无锁有界队列操作。当调用 append 后 WALEdit 和 LogKey 会被封装成 FSWALEntry 类，进而封装成 RingBufferTruck 类放入同一个队列中，然后工作线程会被阻塞，等待 notify 来唤醒。Disruptor 框架中有且仅有一个消费者线程工作，从队列中一次取出 RingBufferTruck 对象，然后根据选项操作：
- 如果 RingBufferTruck 对象中封装的是 FSWALEntry，就会执行文件 append 操作，将记录追加写入 HDFS 文件中，此时数据可能并没有实际酷哦盘，而只是写入到文件缓存
- 如果 RingBufferTruck 对象是 SyncFuture，会调用线程池的线程异步地批量刷盘，刷盘成功之后唤醒工作线程完成 HLog 的 sync 操作

KeyValue 写入 Region 分为两步：追加写入 HLog，然后写入 MemStore。MemStore 使用 ConcurrentSkipListMap 来实际存储 KeyValue，能够后支持大规模并发写入，且本身是有序存储的，有利于数据落盘和提升 MemStore 中的 KeyVlaue 查找性能。KeyValue 写入 MemStore 并不会每次都随机在堆上创建一个内存对象，然后再放到 ConcurrentSkipListMap 中，这会带来非常严重的内存碎片，进而可能频繁触发 Full GC。HBase 使用 MemStore-Local Allocation Buffer(MSLAB)机制预先申请一个大的(2M) Chunk 内存，写入的 KeyValue 会进行一次封装，顺序拷贝这个 Chunk 中，这样 MemStore 中的数据从内存 flush 到硬盘的时候，JVM 内存留下来的就不再是小的无法使用的内存碎片，而是大的可用的内存碎片。MemStore 的写入流程可以分为 3 步：
- 检查当前可用的 Chunk 是否写满，如果写满则重新申请一个 2M 的 Chunk
- 将当前 KeyValue 在内存中重新构建，在可用 Chunk 的指定 offset 处申请内存创建一个新的 KeyValue 对象
- 将新创建的 KeyValue 对象写入 ConcurrentSkipListMap 中

HBase 会在以下集中情况触发 flush 操作：
- MemStore 级别限制：当 Region 中任意一个 MemStore 的大小达到了上限(hbase.hregion.memstore.flush.size，默认 128M)，会触发 MemStore 刷新
- Region 级别限制：当 Region 中所有 MemStore 的大小总和达到上限(hbase.hregion.memstore.block.multilier * hbase.hregion.memstore.flush.size)，会触发 MemStore 刷新
- RegionServer 级别限制：当 RegionServer 中 MemStore 的大小总和超过低水位阈值 hbase.regionserver.global.memstore.size.lower.limit * hbase.regionserver.global.memstore.size，RegionServer 开始强制执行 flush，先 flush MemStore 最大的 Region，在 flush 次大的，依次执行。如果此时写入吞吐量依然很高，导致总 MemStore 大小超过高水位阈值 hbase.regionserver.global.memstore.size，RegionServer 会阻塞更新并强制执行 flush，直到总 MemStore 大小下降到低水位阈值
- 当一个 RegionServer 中 HLog 数量达到上限(hbase.regionserver.maxlogs)时，系统会选取最早的 HLog 对应的一个或多个 Region 进行 flush
- HBase 定期刷新 MemStore：默认周期为 1 小时，以确保 MemStore 不会长时间没有持久化。为避免所有的 MemStore 在同一时间都进行 flush 操作而导致的问题，定期 flush 操作有一定时间的随机延迟
- 手动执行 flush：通过 shell 命令 flush 'tablename' 或者 flush 'regionname' 分别对一个表或者一个 Region 进行 flush

HBase 采用了类似于二阶段提交的方式将整个 flush 过程分为了三个阶段：
- prepare 阶段：遍历当前 Region 中所有 MemStore，将 MemStore 中当前数据集 CellSkipListSet(采用 ConcurrentSkipListMap) 做一个快照 snapshot，然后再新建一个 CellSkipListMap 接收新的数据写入。prepare 阶段需要添加 updateLock 对写请求阻塞，结束之后会释放该锁，持锁时间很短
- flush 阶段：遍历所有 MemStore，将 prepare 阶段生成的 snapshot 持久化为临时文件，放入目录 .tmp 下，这个过程涉及到磁盘 IO，因此比较耗时
- commit 阶段：遍历所有的 MemStore，将 flush 阶段生成的临时文件移到指定的 ColumFamily 目录下，针对 HFile 生成对应的 storefile 和 Reader，把 storefile 添加到 Store 的 storefile 列表中，最后再清空 prepare 阶段生成的 snapshot

HBase 执行 flush 操作后将内存中的数据按照特定格式写入 HFile 文件。MemStore 中 KV 在 flush 成 HFile 时首先构建 Scanned Block 部分，即 KV 写入之后首先构建 Data Block 并依次写入文件，形成 Data Block 的过程中也会依次构建形成 Leaf index Block、Bloom Block 并依次写入文件。一旦 MemStore 中所有 KV 都写入完成，Scanned Block 部分就构建完成。

#### 构建 Scanned Block
MemStore 中 KV 数据写入 HFile 分为 4 个步骤：
- MemStore 执行 flush，首先新建一个 Scanner，这个 Scanner 从存储 KV 数据的 CellSkipListSet 中依次从小到大读出每个 cell。读取的顺序性保证了 HFile 文件中数据存储的顺序性，同时读取的顺序性是保证 HFile 索引构建以及布隆过滤器 Meta Block 构建的前提
- appendGeneralBloomFilter：在内存中使用布隆过滤器算法构建 BloomBlock
- appendDeleteFamilyBloomFilter：针对标记为 DeleteFamily 或者 DeleteFamilyVersion 的 cell，在内存中使用布隆过滤器算法构建 Bloom Block
-  HFile.Writer.append：将 cell 写入 Data Block 中

#### 构建 Bloom Block
Bloom Block 是布隆过滤器的存储，在内存中维护了多个名为 chunk 的数据结构，一个 chunk 主要由两个元素组成：
- 一块连续的内存区域，主要存储一个特定长度的数组。默认数组中所有位都是 0，对于 row 类型的布隆过滤器，cell 进来之后会对其 rowkey 执行 hash 映射，将其映射到位数组的某一位，该位的值修改为 1
- firstkey 是第一个写入该 chunk 的 cell 的 rowkey，用来构建 Bloom Index Block

cell 写进来之后，首先判断当前 chunk 是否已经写满，写满的标准是这个 chunk 容纳的 cell 个数是否超过阈值，如果超过阈值，就会重新申请一个新的 chunk，并将当前 chunk 放入 ready chunks 集合中，如果没有写满则根据布隆过滤器算法使用多个 hash 函数分别对 cell 的 rowkey 进行映射，并将相应的数组位置为 1

#### 构建 Data Block
一个 cell 在内存中生成对应的布隆过滤器信息之后就会写入 Data Block，写入过程分为两步：
- Encoding KeyValue：使用特定的编码对 cell 进行编码处理，HBase 中主要的编码器有 DiffKeyDeltaEncoder、FastDiffDeltaEncoder 等。编码的基本思路是根据上一个 KeyValue 和当前 KeyValue 比较之后取 delta，即 rowkey、column、family 以及 column 分别进行比较然后取 delta。假如前后两个 KeyValue 和 rowkey 相同，当前 rowkey 就可以使用特定的一个 flag 标记，不需要再完整地存储整个 rowkey，这样可以极大地减少存储空间
- 将编码后的 KeyValue 写入 DataOutputStream

随着 cell 的不断写入，当前 Data Block 会因为大小超过阈值(默认 64K)而写满，写满后 DataBlock 会将 DataOutputStream 的数据 flush 到文件，该 Data Block 此时完成落盘。

#### 构建 Leaf Index Block
Data Block 完成落盘之后会立刻在内存中构建一个 Leaf Index Entry 对象，并将该对象加入到当前 Leaf Index Block。Leaf Index Entry 对象有三个重要的字段：
- fistKey：落盘 Data Block 的第一个 key，用来作为索引节点的实际内容，在索引树执行索引查找的时候使用
- blockOffset：落盘 Data Block 在 HFile 文件中的偏移量，用于索引目标确定后快速定位目标 Data Block
- blockDataSize：落盘 Data Block 的大小，用于定位到 Data Block 之后的数据加载

Leaf Index Block 会随着 Leaf Index Entry 的不断写入慢慢变大，一旦大小超过阈值(默认 64KB)，就需要 flush 到文件执行落盘。需要注意的是，Leaf Index Block 落盘是追加写文件的，所以就会形成 HFile 中 Data Block、Leaf Index Block 交叉出现的情况。

和 Data Block 落盘流程一样，Leaf Index Block 落盘之后还需要再往上构建 Root Index Entry 并写入 Root Index Block 形成索引树的根结点，但是根结点并没有追加写入 Scanned Block 部分，而是在最后写入 Load-on-open 部分。

HFile 文件中索引树的构建是由低向上发展的，先生成 Data Block，然后再生成 Leaf Index Block，最后生成 Root Index Block。而检索 rowkey 时先在 Root Index Block 中查找定位到某个 Leaf Index Block，再在 Leaf Inex Block 中二分查找定位到某个 Data Block，最后将 Data Block 加载到内存进行遍历查找

#### 构建 Bloom Block Index
完成 Data Block 落盘还有一件非常重要的事情：检查是否有已经写满的 Bloom Block，如果有将该 Bloom Block 追加写入文件，在内存中构建一个 Bloom Index Entry 并写入 Bloom Index Block。

flush 操作的不同触发方式对用户请求影响的程度不尽相同，正常情况下，大部分 MemStore Flush 操作都不会对业务读写产生太大影响，然而一旦触发 RegionServer 级别限制导致 flush，就会对用户请求产生较大的影响，在这中情况下系统会阻塞所有落在该 RegionServer 上的写入操作，直至 MemStore 中数据量降到配置阈值内。


### Bulk Load

如果有大量数据需要导入到 HBase 系统，此时调用 HBase API 进行处理极有可能会给 RegionServer 带来较大的写入压力：
- 引起 RegionServer 频繁 flush，进而不断 compact、split 影响集群稳定性
- 引起 RegionServer 频繁 GC，影响集群稳定性
- 消耗大量 CPU 资源、带宽资源、内存资源以及 IO 资源，与其他业务产生资源竞争
- 在某些场景下，比如平均 KV 大小比较大的场景，会耗尽 RegionServer 的处理线程，导致集群阻塞

HBase 提供了另一种将数据写入 HBase 集群的方法：BulkLoad。BulkLoad 首先使用 MapReduce 将待写入集群数据转换为 HFile 文件，再直接将这些 HFile 文件加载到在线集群中，BulkLoad 没有将写请求发送给 RegionServer 处理，可以有效避免 RegionServer 压力较大导致的问题。

BulkLoad 主要由两个阶段组成：
- HFile 生成阶段。这个阶段会运行一个 MapReduce 任务，mapper 方法将据组装成一个复合 KV，其中 key 是 rowkey，value 可以是 KeyValue 对象、Put 对象甚至 Delete 对象；reduce 方法由 HBase 负责，通过方法 HFileOutputFormat2.configureIncrementlLoad() 进行配置，这个方法主要负责以下事项：
  - 根据表信息配置一个全局有序的 partitioner
  - 将 partitioner 文件上传到 HDFS 集群并写入 DistributedCache
  - 设置 reduce task 的个数为目标表 Region 的个数
  - 设置输出 key-value 类满足 HFileOutputFormat 所规定的格式要求
  - 根据类型设置 reducer 执行相应的排序(KeyValueSortReducer 或者 PutSortReducer)
- HFile 导入阶段。HFile 准备就绪之后，就可以使用工具 complietebulkload 将 HFile 加载到在线 HBase 集群。complitebulkload 工具负责以下工作
  - 依次检查第一步生成的所有 HFile 文件，将每个文件映射到对应的 Region
  - 将 HFile 文件移动到对应 Region 所在的 HDFS 文件目录下
  - 告知 Region 对应的 RegionServer，加载 HFile 对外提供服务

如果在 BulkLoad 的中间过程中 Region 发生了分裂，completebulkload 工具会自动将对应的 HFile 文件按照新生成的 Region 边界切分成多个 HFile 文件，保证每个 HFile 都能与目标表当前 Region 相对应，但这个过程需要读取 HFile 内容，因而并不高效。需要尽量减少 HFile 生成阶段和 HFile 导入阶段的延迟，最好能够在 HFile 生成之后立刻执行 HFile 导入

通常有两种方法调用 completebulkload 工具：
```shell
bin/hbase org.apache.hadoop.hbase.tool.LoadIncrementalHFiles <hdfs://storefileoutput> <tablename>

bin/hadoop jar ${HBASE_HOME}/hbase-server-Version.jar completebulkload <hdfs://storefileoutput> <tablename>
```
如果表没在集群中，工具会自动创建表。

### HBase 读取流程
HBase 读数据的流程更加负责，主要有两个方面的原因：
- HBase 一次范围查询可能会涉及多个 Region、多块缓存甚至多个数据存储文件
- HBase 中更新操作是插入了新的以时间戳为版本数据，删除操作是插入了一条标记为 delete 标签的数据，读取的过程需要根据版本以及删除标签进行过滤

#### Client-Server 交互
Client 首先会从 ZooKeeper 中获取元数据 hbase:meta 表所在的 RegionServer，然后根据待读写 rowkey 发送请求到元数据所在的 RegionServer，获取数据所在的目标 RegionServer 和 Region 信息并保存到本地，最后将请求进行封装发送到目标 RegionServer 进行处理

HBase Client 端的 scan 操作并没有设计为一次 RPC 请求，这是因为一次大规模的 scan 操作很有可能就是一次全表扫描，扫描结果非常大，通过一次 RPC 将大量扫描结果返回客户端会带来至少两个严重的后果：
- 大量数据传输会导致集群网络带宽等系统资源短时间被大量占用，严重影响集群中其他业务
- 客户端很可能因为内存无法缓存这些数据而导致 OOM

HBase 会根据设置条件将一次大的 scan 操作拆分为多个 RPC 请求，每个 RPC 请求称为一次 next 请求，每次只返回规定数量的结果。单次 RPC 请求的数据条数由 caching 设定，默认为 Integer.MAX_VALUE，每次 RPC 请求获取的数据都会缓存到客户端，该值如果设置过大可能会因为一次获取到的数据量太大导致服务器/客户端内存 OOM，而设置太小会导致一次大的 scan 进行太多次 RPC，网络成本高。

对于一行数据的数据量非常大的情况，为了防止返回一行数据但数据量很大的情况，客户端可以通过 setBatch 方法设置一次 RPC 请求的数据列数量。

客户端还可以通过 setMaxResultSize 方法设置每次 RPC 请求返回的数据量大小，默认 2G

#### Server 端 Scan
一次 scan 可能会同时扫描一张表的多个 Region，对于这种扫描客户端会根据 hbase:meta 元数据将扫描的起始区间 [startKey, stopKey] 进行切分，切分成多个相互独立的查询子区间，每个子区间对应一个 Region。

HBase 中每个 Region 都是一个独立的存储引擎，因此客户端可以将每个子区间请求分别发送给对应的 Region 进行处理。RegionServer 接收到客户端的 get/scan 请求之后做了两件事情：
- 构建 scanner iterator 体系
- 执行 next 函数获取 KeyValue，并对其进行条件过滤

Scanner 的核心体系包括三层 Scanner：RegionScanner, StoreScanner, MemStoreScanner 和 StoreFileScanner。
- 一个 RegionScanner 由多个 StoreScanner 构成，一张表由多少个列簇组成，就有多少个 StoreScanner，每个 StoreScanner 负责对应 Store 的数据查找
- 一个 StoreScanner 由 MemStoreScanner 和 StoreFileScanner 构成，每个 StoreScanner 会为当前该 Store 中每个 HFile 构造一个 StoreFileScanner 用于执行对应文件的检索，同时会为对应 MemStore 构造一个 MemStoreScanner 用于执行 MemStore 的数据检索

RegionScanner 以及 StoreScanner 并不负责实际查找操作，而是承担调度任务，负责 keyValue 最终查找的是 StoreFileScanner 和 MemStoreScanner

Scanenr 创建完成之后还需要几个非常关键步骤：
- 淘汰部分不满足查询条件的 Scanner，通过一些查询条件过滤肯定不存在待查找 KeyValue 的 HFile。主要过滤策略有：Time Range 过滤、Rowkey Range 过滤以及布隆过滤器
- 每个 Scanner seek 到 startKey，在每个 HFile 文件(或 MemStore)中 seek 扫描起始点 startKey，如果没有找到 startKey 则 seek 下一个 KeyValue 地址
- KeyValueScanner 合并构建最小堆，将该 Store 中的所有 StoreFileScanner 和 MemStoreScanner 合并形成一个最小堆，按照 Scanner 排序规则由小到大进行排序。最小堆管理的 Scanner 可以保证取出来的 KeyValue 都是最小的，这样一次不断的取堆顶就可以由小到大获取目标 KeyValue 集合，保证有序性

经过 Scanner 体系的构建，KeyValue 此时已经可以由小到大依次经过 KeyValueScanner 获得，但这些 KeyValue 是否满足用户设定的 TimeRange 条件、版本号条件以及 Filter 条件还需要进一步的检查：
- 检查该 KeyValue 的 KeyType 是否是 Delete/DeleteColumn/DeleteFamily 等，如果是则直接忽略该列所有其他版本
- 检查该 KeyValue 的 Timestamp 是否在用户设定的 Timestamp Range 范围，如果不在该范围则忽略
- 检查该 KeyValue 是否满足用户设置的各种 filter 过滤器，如果不满足则忽略
- 检查该 KeyValue 是否满足用户查询中设定的坂本数，过滤不符合版本数的数据

过滤 StoreFile 的手段主要有三种：
- 根据 KeyRange 过滤：因为 StoreFile 中所有 KeyValue 数据都是有序排列的，所以如果待检索 row 范围 [startrow, stoprow] 与文件起始 key 范围 [firstkey, lastkey] 没有交集，就可以过滤掉该 StoreFile
- 根据 TimeRange 过滤：StoreFile 元数据有一个关于该 HFile 的 TimeRange 属性[miniTimestamp, maxTimestamp]，如果待检索的 TimeRange 与该文件时间范围内没有交集，就可以过滤掉该 StoreFile，如果该文件所有数据已经过期也可以过滤淘汰
- 根据布隆过滤器过滤：根据待检索的 rowkey 获取对应的 Bloom Block 并加载到内存(通常情况下，热点 Bloom Block 会常驻内存)，再用 hash 函数对待检索 rowkey 进行 hash，根据 hash 后的结果在布隆过滤器数据中进行寻址，即可确定待检索 rowkey 是否一定不存在于该 HFile

在一个 HFile 文件中 seek 待查找的 key 的过程分为 4 步：
- 根据 HFile 索引树定位目标 Block
- BlockCache 中检索目标 Block
- HDFS 文件中检索目标 Block
- 从 Block 中读取待查找 KeyValue

HRegionServer 打开 HFile 时会将所有 HFile 的 Trailer 部分和 Load-on-open 部分加载到内存，Load-on-open 部分有非常重要的索引树根结点 Root Index Block，rowkey 在 Root Index Block 中通过二分查找定位到需要加载的 Data Block，然后遍历 Data Block 找到对应的 KeyValue

Block 缓存到 Block Cache 之后会构建一个 Map，key 是 BlockKey，由 HFile 的名称以及 Block 在 HFile 中的偏移量构成，全局唯一；value 是 Block 在 BlockCache 中的地址，如果在 BlockCache 中没有找到待查 Block，就需要在 HDFS 文件中查找

文件索引提供的 Block offset 以及 Block datasize 这两个元素可以在 HDFS 上读取到对应的 Data Block 内容(HFileBlock.FSReaderImpl.readBlockData 方法)，这个流程涉及 4 个组件：HBase、NameNode、DataNode 以及磁盘。HBase 会在加载 HFile 的时候为每个 HFile 新建一个从 HDFS 读取数据的数据流 FSDataInputStream，之后所有对该 HFile 的读取操作都会使用这个文件几倍的 InputStream 进行操作。

使用 FSDataInputStream 读取 HFile 中的数据块，命令下发到 HDFS 首先会联系 NameNode 组件，NameNode 组件会做两件事：
- 找到属于这个 HFile 的所有 HDFSBlock 列表，确认待查找数据在哪个 HDFSBlock 上(HDFS 的 DataBlock 大小等于 128M)
- 确认定位到的 HDFSBlock 在哪些 DataNode 上，选择一个最优 DataNode 返回给客户端

NameNode 告知 HBase 可以去特定 DataNode 上访问特定 HDFSBlock，之后 HBase 请求对应的 DataNode 数据块，DataNode 找到指定的 HDFSBlock，seek 到指定偏移量，从磁盘读出指定大小的数据返回。DataNode 读取数据实际上是向磁盘发送读取指令，磁盘接收到读取指令之后会移动磁头到给定位置，读取出完整的 64K 数据返回

HDFS 的 Block 设计为 128M 是因为当数据量大到一定程度，如果 Block 太小会导致 Block 元数据非常庞大，使得 NameNode 成为整个集群瓶颈；HBase 的缓存策略是缓存整个 Block，如果 Block 太大容易导致缓存内存很容易耗尽

HFile Block 由 KeyValue 构成，但这些 KeyValue 并不是固定长度的，只能遍历扫描查找。

## Coprocessor
HBase 使用 Coprocessor 机制使用户可以将自己编写的程序运行在 RegionServer 上，从而在特定场景下大幅提升执行效率。在某些场景下需要把数据全部扫描出来在客户端进行计算，就会有如下问题：
- 大量数据传输可能会成为瓶颈，导致整个业务的执行效率受限于数据传输效率
- 客户端内存可能会因为无法存储如此大量的数据而 OOM
- 大量数据传输可能将集群带宽耗尽，严重影响集群中其他业务的正常读写

如果将客户端的计算代码迁移到 RegionServer 服务端执行，就能很好地避免上述问题。

HBase Coprocessor 分为两种：Observer 和 Endpoint


Observer Coprocessor 提供钩子使用户代码在特定事件发生之前或者之后得到执行，只需要重写对应的方法即可。HBase 提供了 4 中 Observer 接口：
- RegionObserver：主要监听 Region 相关事件
- WALObserver：主要监听 WAL 相关事件，比如 WAL 写入、滚动等
- MasterObserver：主要监听 Master 相关事件，比如建表、删表以及修改表结构等
- RegionServerObserver：主要监听 RegionServer 相关事件，比如 RegionServer 启动、关闭或者执行 Region 的合并等事件

Endpoint Coprocessor 允许将用户代码下推到数据层执行，可以使用 Endpoint Coprocessor 将计算逻辑下推到 RegionServer 执行，通过 Endpoint Coprocessor 可以自定义一个客户端与 RegionServer 通信的 RPC 调用协议，通过 RPC 调用执行部署在服务器的业务代码，Endpoint Coprocessor 执行必须由用户显示触发调用。

自定义的 Coprocessor 可以通过两种方式加载到 RegionServer：通过配置文件静态加载、动态加载

通过静态加载的方式将 Coprocessor 加载到集群需要执行 3 个步骤：
- 将 Coprocessor 配置到 hbase-site.xml 中， hbase.coprocessor.region.classes 配置 RegionObservers 和 Endpoint Coprocessor；hbase.coprocessor.wal.classes 配置 WALObservers；hbase.coprocessor.master.classes 配置 MasterObservers
- 将 Coprocessor 代码放到 HBase 的 classpath 下
- 重启 HBase 集群

静态加载 Coprocessor 需要重启集群，使用动态加载方式则不需要重启，动态加载有 3 中方式：

使用shell:
```shell
# disable 表
disable 'tablename'

# 修改表 schema
alter 'tablename', METHOD=>'table_att', 'Coprocessor'=>'hdfs://....coprocessor.jar|org.libs.hbase.Coprocessor.RegionObserverExample "|"'

# enable 表
enable 'tablename'
```
使用 HTableDescriptor 的 setValue 方法
```java
String path = "hdfs://path/of/coprocess.jar";
HTableDescriptor descriptor = new HTableDescriptor(tableName);
descriptor.setValue("COPROCESSOR$1", path+"|"+RegionObserverExample.class.getCanonicalName() + "|" + Coprocessor.PRIORITY_USER);
```
使用 HTableDescriptor 的 addCoprocessor 方法

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

## 负载均衡
HBase 中的负载均衡是基于数据分片设计，即 Region。HBase 的负载均衡主要涉及 Region 的迁移、合并、分裂等。

### Region 迁移
HBase 的集群负载均衡、故障恢复功能都是建立在 Region 迁移的基础之上，HBase 由于数据实际存储在 HDFS 上，在迁移过程中不需要迁移实际数据而只需要迁移读写服务即可，因此 HBase 的 Region 迁移是非常轻量级的。

Region 迁移虽然是一个轻量级操作，但是实现逻辑依然比较复杂，其复杂性在于两个方面：
- Region 迁移过程中设计多种状态的改变
- Region 迁移过程中设计 Master，ZK 和 RegionServer 等多个组件的互相协调

Region 迁移的过程分为 unassign 和 assign 两个阶段
#### unassign 阶段
unassign 阶段是 Region 从 RegionServer 下线，总共涉及 4 个状态变化
- Master 生成事件 M_ZK_REGION_CLOSING 并更新到 ZK 组件，同时将本地内存中该 Region 的状态修改为 PENDING_CLOSE
- Master 通过 RPC 发送 close 命令给拥有该 Region 的 RegionServer，令其关闭该 Region
- RegionServer 接收到 Master 发送过来的命令后，生成一个 RS_ZK_REGION_CLOSING 事件，更新到 ZK
- Master 监听到 ZK 节点变动后，更新内存中 Region 的状态为 CLOSING
- RegionServer 执行 Region 关闭操作。如果该 Region 正在执行 flush 或者 Compaction，则等待其完成；否则将该 Region 下的所有 MemStore 强制 flush，然后关闭 Region 相关服务
- RegionServer 执行完 Region 关闭操作后生成事件 RS_ZK_REGION_CLOSED 更新到 ZK，Master 监听到 ZK 节点变化后，更新该 Region 的状态为 CLOSED

#### assign 阶段
assign 阶段是 Region 从目标 RegionServer 上线，也会涉及到 4 个状态变化
- Master 生成事件 M_ZK_REGION_OFFLINE 并更新到 ZK 组件，同时将本地内存中该 Region 的状态修改为 PENDING_OPEN
- Master 通过 RPC 发送 open 命令给拥有该 Region 的 RegionServer，令其打开 Region
- RegionServer 接收到命令之后，生成一个 RS_ZK_REGION_OPENING 事件，并更新到 ZK
- Master 监听到 ZK 节点变化后，更新内存中 Region 的状态为 OPENING
- RegionServer 执行 Region 打开操作，初始化相应的服务
- 打开完成之后生成事件 RS_ZK_REGION_OPENED 并更新到 ZK，Master 监听到 ZK 节点变化后，更新该 Region 状态为 OPEN

整个 unassign 和 assign 过程涉及 Master、RegionServer 和 ZK 三个组件，三个组件的职责如下：
- Master 负责维护 Region 在整个过程中的状态变化
- RegionServer 负责接收 Master 的指令执行具体 unassign 和 assign 操作，即关闭 Region 和打开 Region 的操作
- ZK 负责存储操作过程中的事件，ZK 有一个路径为 /hbase/region-in-transaction 节点，一旦 Region 发生 unassign 操作，就会在这个节点下生成一个子节点，Master 会监听此节点，一旦发生任何事件，Master 会监听到并更新 Region 的状态

Region 在迁移的过程中涉及到多个状态的变化，这些状态可以记录 unassign 和 assign 的进度，在发生异常时可以根据具体进度继续执行。Region 的状态会存储在三个区域：
- meta 表，只存储 Region 所在的 RegionServer，并不存储迁移过程中的中间状态，如果 Region 迁移完成则会更新为新的对应关系，如果迁移过程失败则保存的是之前的映射关系
- master 内存，存储整个集群所有的 Region 信息，根据这个信息可以得出此 Region 当前以什么状态在哪个 RegionServer 上。Master 存储的 Region 状态变更都是由 RegionServer 通过 ZK 通知给 Master 的，所以 Master 上的 Region 状态变更总是滞后于真正的 Region 状态变更，而 WebUI 上看到的 Region 状态都是来自于 Master 的内存信息
- ZK，存储的是临时性的状态转移信息，作为 Master 和 RegionServer 之间反馈 Region 状态的通信，Master 可以根据 ZK 上存储的状态作为依据据需进行迁移操作

### Region 合并
Regin 合并用于空闲 Region 很多从而导致集群管理运维成本增加的场景，通过使用在线合并功能将这些 Region 与相邻的 Region 合并，减少集群中空闲的 Region 个数。

Region 合并的主要流程如下：
- 客户端发送 merge 请求给 Master
- Master 将待合并的所有 Region 都 move 到同一个 RegionServer 上
- Master 发送 merge 请求给该 RegionServer
- RegionServer 启动一个本地事务执行 merge 操作
- merge 操作将待合并的两个 Region 下线，并将两个 Region 的文件进行合并
- 将这两个 Region 从 hbase:meta 中删除，并将新生成的 Region 添加到 hbase:meta 中
- 将新生成的 Region 上线

HBase 使用 merge_region 命令执行 Region 合并，merge_region 操作是异步的，需要在执行一段时间之后手动检测合并是否成功，默认情况下 merge_region 只能合并相邻的两个 Region，如果可选参数设置为 true 则可以强制合并非相邻的 Region，风险较大不建议生产使用：
```shell
merge_region 'regionA', 'regionB', true
```

### Region 分裂
Region 分裂是 HBase 实现可扩展性的基础，在 HBase 中 Region 分裂有多种触发策略可以配置，一旦触发，HBase 会寻找分裂点，然后执行真正的分裂操作。

#### Region 分裂触发策略
HBase 提供了 6 中分裂触发策略，每种触发策略都有各自适用的场景，可以在表级别选择不同的分裂触发策略：
- ConstantSizeRegionSplitPolicy：表示一个 Region 中最大 Store 的大小超过设置阈值(hbase.hregion.max.filesize)之后触发分裂。这种策略实现简单，但是对于大表和小表没有明显的区分，阈值设置较大则对大表比较友好，而小表可能不会分裂，极端情况下只有一个 Region，这对 RegionServer 压力比较大；如果阈值设置的比较小，则大表会在集群中产生大量的 Region，这对集群的管理来说增加了负担
- IncreasingToUpperBoundRegionSplitPolicy：这种策略也是在一个 Region 中最大的 Store 大小超过阈值之后触发分裂，但是这种策略的阈值并不是固定的，而是和 Region 所属表在的 RegionServer 上的 Region 数量有关，其值为 ```regions * regions * regions * flushsize * 2```，这个值得上限为 MaxRegionFileSize，这种策略会使得很多小表就会产生大量小的 Region
- SteppingSplitPolicy：这种策略的分裂阈值大小和待分裂 Region 所属表当前 RegionServer 上的 Region 个数有关，如果 Region 个数为 1，分裂阈值为 flushsize * 2，否则为 MaxRegionFileSize，这种策略不会使得小表再产生大量的小 Region

Region 触发分裂的策略可以在创建表的时候设置：
```shell
create 'table', {NAME=>'cf', SPLIT_POLICY=>'org.apache.hadoop.hbase.regionserver.ConstantSizeRegionSplitPolicy'}
```

#### 寻找分裂点
Region 触发分裂之后需要寻找分裂点，除了手动执行分裂可以指定分裂点之外，Region 的分裂点定义为：整个 Region 中最大 Store 的最大文件最中心的一个 Block 的首个 rowkey，如果 rowkey 是整个文件的首个 rowkey 或最后一个 rowkey 时则没有分裂点，此时整个 Region 中只有一个 block

#### Region 分裂流程
Region 的分裂是在一个事务当中，整个分裂过程分为三个阶段：
- prepare 阶段：在内存中初始化两个子 Region，每个 Region 对应一个 HRegionInfo 对象，同时会生成一个 transaction journal 对象用来记录分裂的进程
- execute 阶段：execute 阶段是整个分裂过程的核心阶段，总共有 8 个步骤：
  - RegionServer 将 ZK 节点 /region-in-transaction 中该结点的状态更改为 SPLITING
  - Master 通过 watch 节点 /region-in-transaction 检测到 Region 状态改变，并修改内存中 Region 的状态，在 Master 页面 RIT 模块可以看到 Region 执行 split 的状态信息
  - 在父存储目录下新建临时文件夹 .split，保存 split 后的 daughter region 信息
  - 关闭父 Region，父 Region 关闭数据写入并触发 flush 操作，将写入 Region 的数据全部持久化到磁盘，此后短时间内客户端落在父 Region 上的请求都会抛出 NotServingRegionException
  - 在 .split 文件夹下新建两个子文件夹，称为 daughter A，daughter B，并在文件夹中生成 reference 文件，分别指向父 Region 中对应文件。reference 文件是一个引用文件，文件内容并不是用户数据，而是由两部分组成：分裂点 splitkey 和 boolean 类型的变量表示该 reference 文件引用的是父文件的上半部分(true)或者下半部分(false)，使用 hadoop 命令 ```hadoop dfs -cat /hbase-rsgroup/data/default/...``` 可以查看 reference 文件内容
  - 父 Region 分裂为两个子 Region 后，将 daughter A、daughter B 拷贝到 HBase 根目录下，形成两个新的 Region
  - 父 Region 通知修改 hbase:meta 表后下线，不再提供服务。下线后父 Region 在 meta 表中的信息并不会马上删除，而是将 meta 表中的 split 列、offline 列标注为 true，并记录两个子 Region
  - 开启 daughter A、daughter B 两个子 Region。通知修改 hbase:meta 表正式对外提供服务
- rollback 阶段：如果 execute 阶段出现异常，则执行 rollback 操作。为了实现回滚，整个分裂过程分为很多子阶段，回滚程序会根据当前进展到哪个子阶段清理对应的垃圾数据，整个分裂的过程的阶段由 RegionMergeTransactionPhase 类定义

Region 分裂是一个比较复杂的过程，涉及到父 Region 中 HFile 文件分裂，子 Region 生成，meta 元数据更新等很多个子步骤，为了实现原子性，HBase 使用状态机的方式保存分裂过程中的每个子步骤状态，这样一旦出现异常，系统可以根据当前所处的状态决定是否回滚，以及如何回滚。目前这些中间状态都只存储在内存中，一旦在分裂过程中出现 RegionServer 宕机的情况则有可能出现分裂处于中间状态的情况，即 RIT 状态，这种情况下需要使用 HBCK 工具查看并分析解决方案。

在 2.0 版本之后，HBase 实现了新的分布式事务框架 Procedure V2，新框架使用类似 HLog 的日志文件存储这种单机事务的中间状态，因此可以保证即使在事务执行过程中参与者发生了宕机，依然可以使用对应日志文件作为协调者，对事务进行回滚操作或者重试提交，从而大大减少甚至杜绝 RIT 现象。

通过 reference 文件查找数据分为两步：
- 根据 reference 文件名(父 Region 名 + HFile 文件名)定位到真实数据所在文件路径
- 根据 reference 文件内容记录的两个重要字段确定实际扫描范围，top 字段表示扫描范围是 HFile 上半部分还是下半部分，如果 top 为 true 则表示扫描的范围为 [firstkey, splitkey)，如果 top 为 false 则表示扫描的范围为 [splitkey, endkey)

父 Region 的数据迁移到子 Region 目录的时间发生在子 Region 执行 Major Compaction 时，在子 Region 执行 Major Compaction 时会将父 Region 目录中属于该子 Region 中所有的数据读取出来，并写入子 Region 目录数据文件中。

Master 会启动一个线程定期遍历检查所处于 splitting 状态的 Region，确定父 Region 是否可以被清理，检查过程分为两步：
- 检测线程首先会在 meta 表中读出所有 spit 列为 true 的 Region，并加载出其分裂后生成的两个子 Region(meta 表中 splitA 和 splitB 两列)
- 检查两个子 Region 是否还存在引用文件，如果都不存在引用文件就可以认为该父 Region 对应的文件可以被删除

HBCK 可以查看并修复在 split 过程中发生异常导致 region-in-transaction 问题，主要命令包括：
```shell
-fixSplitParents
-removeParents
-fixReferenceFiles
```

负载均衡一个重要的应用场景就是系统扩容，通过负载均衡策略使得所有节点上的负载保持均衡，从而避免某些节点由于负载过重而拖慢甚至拖垮整个集群。在选择负载均衡策略之前需要明确系统的负载是什么，可以通过哪些元素来刻画，并指定相应的负载迁移计划。HBase 目前支持两种负载均衡策略：
- SimpleLoadBalancer：保证每个 RegionServer 的 Region 个数基本相等。因此在 SimpleLoadBalancer 策略中负载就是 Region 的个数，集群负载迁移计划就是从个数较多的 RegionServer 上迁移到个数较少的 RegionServer 上。这种负载均衡策略并没有考虑到 RegionServer 上的读写 QPS 以及 Region 中数据量的问题，可能会导致热点数据落在统一个 RegionServer 上从而导致节点负载较重
- StochasticLoadBalancer：对于负载的定义不再是 Region 个数这个简单，而是由多种独立负载加权计算的复合值，包括 Region 个数(RegionCountSkewCostFunction)、Region 负载、读请求数(ReadRequestCostFunction)、写请求数(WriteRequestCostFunction)、StoreFile 大小(StoreFileCostFunction)、MemStore 大小(MemStoreSizeCostFunction)、数据本地率(LocalityCostFunction)、移动代价(MoveCostFunction) 等，系统使用这个代价值来评估当前 Region 是否均衡，越均衡代价值越低

通过配置文件可以设置具体的负载均衡策略：
```xml
<property>
  <name>hbase.master.loadbalancer.class</name>
  <value>org.apache.hadoop.hbase.master.balancer.SimpleLoadBalancer</value>
</property>
```
### 宕机恢复原理
HBase 系统中主要有两类服务进程：Master 进程和 RegionServer 进程。Master 主要负责集群管理调度，发生故障的概率比较低，RegionServer 负责用户的读写，压力会比较大，逻辑比较复杂，因此发生故障的概率较大。RegionServer 有一些常见的异常：
- Full GC 异常：长时间的 Full GC 是导致 RegionServer 宕机的最主要原因
- HDFS 异常：RegionServer 读写数据是直接操作 HDFS 的，HDFS 异常会导致 RegionServer 异常
- 机器宕机：物理节点宕机会导致 RegionServer 进程直接挂掉

HBase 采用了热备方式实现 Master 的高可用，集群中至少会启动两个 Master 进程，进程启动后会在 ZK 上的 Master 节点注册，注册成功后则会成为 Active Master，未注册成功则为 Backup Master，当 Active Master 异常，Backup Master 就会竞争注册成为 Active Master。

RegionServer 宕机之后 HBase 会在检测到宕机之后将该 RegionServer 上的所有 Region 重新分配到集群中其他正常的 RegionServer 上，再根据 HLog 进行数据丢失恢复，恢复完成后对外提供服务：
- Master 检测到 RegionServer 宕机：HBase 检测宕机是通过 ZK 实现的，正常情况下 RegionServer 会周期性的向 ZK 发送心跳，查过一定时间(SessionTimeout) 没有接收到心跳，ZK 就会认为 RegionServer 宕机离线，并将消息通知给 Master
- 切分未持久化数据的 HLog 日志：RegionServer 宕机之后已经写入 MemStore 但还没有持久化到文件的这部分数据会丢失，通过 HLog 可以恢复这部分数据。HLog 中所有Region 的数据都混合存储在同一个文件中，为了使这些数据能够按照 Region 进行组织回放，需要将 HLog 日志进行切分再合并，同一个 Region 的数据最终合并在一起，方便后续按照 Region 进行数据恢复
- Master 重新分配宕机 RegionServer 上的 Region：RegionServer 宕机后 Region 属于不可用状态，所有路由到这些 Region 的请求都会返回异常。Master 会将这些不可用的 Region 重新分配到其他 RegionServer 上，但此时这些 Region 还并没有上线，因为之前存储在 MemStore 中还没有落盘的数据需要回放
- 回放 HLog 日志补救数据：根据 HLog 按照 Region 切分的结果，在指定的 RegionServer 上回放，将还没有来得及落盘的数据补救回来
- 修复完成之后，对外提供服务，完成宕机恢复

#### RegionServer 宕机检测
HBase 使用 ZK 协助 Master 检测 RegionServer 宕机，所有 RegionServer 在启动之后都会在 ZK 节点 /rs 上注册一个子节点，这个子节点的类型为临时节点，一旦连接在该节点上的客户端因为某些原因发生会话超时，这个临时节点会自动消失，并通知 watch 在该临时节点上的其他客户端。

在一些情况下，由于 RegionServer 发生了长时间的 GC 导致心跳长时间断开，这是临时节点就会离线，导致 Master 认为 RegionServer 宕机。ZK 的会话超时时间可以在配置文件中设置，参数是 zookeeper.session.timeout，默认是 180s，但是此参数的调整需要配合 ZK 服务器的参数 tickTime, minSessionTimeout, maxSessionTimeout

#### 切分 HLog
一个 RegionServer 默认只有一个 HLog，RegionServer 中的所有 Region 的日志都是混合写入该 HLog 中，而日志回放时根据 Region 为单元进行的，因此需要将 HLog 中的数据按照 Region 进行分组，这个过程称为 HLog 切分。

HBase 最初阶段日志切分的整个过程由 Master 控制执行，整个切分合并流程分为三步：
- 将待切分日志文件重命名，避免 RegionServer 没有宕机而 Master 误认为 RegionServer 宕机，导致 HLog 日志在切分的同时还在不断的写入从而发生数据不一致
- 启动一个读线程依次顺序读出每个 HLog 中所有数据对，根据 HLog key 所属的 Region 写入不同的内存 buffer 中，这样整个 HLog 中的所有数据会完整的按照 Region 进行切分
- 切分完成之后，Master 会为每个 buffer 启动一个独立的写线程，负责将 buffer 中的数据写入各个 Region 对应的 HDFS 目录下。写线程会先将数据写入临时路径：/hbase/table_name/region/recoverd.edits/.tmp 之后重命名为正式路径 /hbase/table_name/region/recoverd.edits/.sequnceidx

切分完成之后，Region 重新分配到其他 RegionServer，最后按照顺序回放对应的 Region 的日志数据，这种日志切分的整个过程只有 Master 参与，在某些场景下需要恢复大量的数据，会加重 Master 的负载压力。

HBase 在后续版本中使用了 Distributed Log Spliting(DLS) 分布式切分 HLog 的机制，它借助 Master 和所有 RegionServer 进行日志切分，其中 Master 是协调者，RegionServer 是实际的工作者，其基本步骤如下：
- Master 将待切分日志路径发布到 ZK 节点 /hbaes/splitWAL 上，每个日志为一个任务，每个任务都有对应的状态，起始状态为 TASK_UNASSIGNED
- 所有 RegionServer 启动之后都注册在这个节点上等待新任务，一旦 Master 发布任务，RegionServer 就会抢占该任务
- 抢占任务实际上要先查看任务状态，如果是 TASK_UNASSIGNED 状态，说明当前没有被占有，如果修改失败，则说明其他 RegionServer 抢占成功
- RegionServer 抢占任务成功之后，将任务分发给相应线程处理，如果处理成功则将该任务对应的 ZK 节点状态修改为 TASK_DONE，如果处理失败则将状态改为 TASK_ERR
- Master 一直监听该 ZK 节点，一旦发生状态修改就会得到通知，如果任务状态变更为 TASK_ERR，则 Master 重新发布该任务；如果任务状态变更为 TASK_ERR，则 Master 将对应的节点删除

假设 Master 当前发布了 4 个任务，即当前需要回放 4 个日志文件，RegionServer 抢占到日志之后分别将任务分发给两个 HLogSplitter 线程进行处理，HLogSplitter 负责对日志文件执行具体的切分，首先读出日志中每一个数据对，根据 HLog key 所属的 Region 写入不同的 Region buffer。每个 Region buffer 都会有一个对应的线程，将 buffer 中的日志数据写入 hdfs 中，写入路径为 /hbase/table/region/sequenceid.temp，然后针对某一 Region 回放日志，将该 Region 对应的所有问及爱你按照 sequenceid 由小到大依次进行回放即可。

Distributed Log Splitting 方式可以很大程度上加快故障恢复的进程，通常可以将故障恢复时间降低到分钟级别，但是这种方式会产生很多日志小文件，产生的文件数将是 M*N(其中 M 是待切分的总 HLog 数量，N 是 RegionServer 上的 Region 个数)。

Distributed Log Replay(DLR) 方案相比于 DLS 方案有一些改动，Region 重新分配打开之后状态设置为 recovering，在此状态下的 Region 可以对外提供给写服务，不能提供读服务，而且不能执行 split, merge 等操作。DLR 方案在分解 HLog 为 Region buffer 之后并没有写入小文件，而是直接执行回放，这样可以大大减少小文件的读写 IO 消耗。DLR 方案需要在配置中通过设置参数 hbase.master.distributed.log.replay=true 来开启，同时 HFile 的格式也要设置为 v3

HBase 故障恢复经历的 4 个核心流程(故障检测、切分 HLog、Region 分配、回放 HLog 日志)中切分 HLog 最为耗时，如果一个 RegionServer 上维护了大量的 Region，当 RegionServer 宕机后需要将 HLog 切分成对应的数量，然后为每个 Region 分配一个 writer 写入到 HDFS。对于 HDFS 来说，每个写入 writer 需要消耗 3 个不同 DataNode 各一个 Xceiver 线程，而 HDFS 集群上每个 DataNode 的 Xceiver 的线程上限为 4096，若 Region 对应的 writer 过多则会导致 HDFS Xceiver 耗尽而导致故障恢复难以进行，从而对整个集群的可用性带来严重影响。

针对这个问题，首先应该控制的是 writer 的个数，为 HLog 中的每一个 Region 设一个缓冲池，每个 Region 的缓冲池有一个阈值上限 hbase.regionserver.hlog.splitlog.buffersize，如果碰到一条新的 HLog Entry 发现对应 Region 的缓冲池没有到达上限，则直接写缓冲，否则选出当前所有缓冲池中超过阈值的缓冲池结合，将这个集合中的缓冲池依次刷新成 HDFS 上的一个新文件，这个过程是放到一个 writer 池中完成，也就能保证任意时刻最多只有指定个数的 writer 在写数据文件而不会造成 Xceiver 被耗尽，副作用就是 split 操作之后产生的文件数变多。如果要开启这个功能需要配置：
```properties
hbase.split.create.writer.limited=true
hbase.regionserver.hlog.splitlog.buffersize=
hbase.regionserver.hlog.write.thread=32
```

另外从集群故障恢复的过程来看，其速度由几个变量决定：
- 故障 RegionServer 的个数
- 故障 RegionServer 上需要切分的 HLog 个数，由参数 hbase.hstore.blockingStoreFile 决定
- HDFS 集群 DataNode 个数
- 每个 RegionServer 上能并行跑的 split worker 个数，由参数 hbase.regionserver.wal.splitters 决定
- 每个 split worker 能开的 writer 线程个数，由 hbase.regionserver.hlog.splitlog.writer.thread 参数决定

由于需要满足 Xceiver 的个数不能超过 HDFS 集群的总数，提高并发只能通过调大每个 RegionServer 上并行的 split worker 或者每个 worker 能开启的 writer 值；对于需要尽快恢复集群，只能控制故障 RegionServer 上需要切分的 HLog 的个数，这个值不能设置的太大，也不能设置的太小，太大会导致故障恢复较慢，太小会导致 MemStore 频繁的进行 flush 操作，影响性能。

## 复制
复制功能为 HBase 跨集群数据同步提供了支撑。HBase 客户端创建一个 Peer（一条主集群到备份集群的复制链路），包含 PeerId, 备份集群的 ZK 地址、是否开启数据同步，以及需要同步的 namespace, table, column family 等，HBase 客户端创建 Peer 的流程如下：
- 将创建 Peer 的请求发送到 Master
- Master 内部实现了一个名为 Procedure 的框架，对于一个 HBase 的管理操作会拆分成多步，每步执行完成之后会将状态信息持久化到 HDFS 上，然后继续执行下一步操作，这样在任何一步出现异常就可以在这一步重新执行而不需要全部重新执行。对于创建 Peer 来说，Procedure 会为该 Peer 创建相关的 ZNode，并将复制相关的元数据保存在 ZK 上
- Master 的 Procedure 会向每一个 RegionServer 发送创建 Peer 的请求，直到所有的 RegionServer 都成功创建 Peer，否则会重试
- Master 返回给 HBase 客户端

在创建完 Peer 后，真正负责数据同步的是 RegionServer 的 ReplicationSource 线程，该线程复制数据的流程如下：
- 在创建 Peer 时，每个 RegionServer 会创建一个 ReplicationSource 线程，ReplicationSource 首先把当前写入的 HLog 都保存在复制队列中，然后再 RegionServer 上注册了一个 Listener，用来监听 HLog Roll 操作，如果 RegionServer 做了 HLog roll 操作，那么 ReplicationSource 收到这个操作后会把这个 HLog 分到对应的 walGroupQueue 里面，同时把 HLog 文件名持久化到 ZK 上，这样重启后还可以接着复制未复制完成的 HLog
- 每个 WalGroupQueue 后端有一个 ReplicationSourceWALReader 的线程，这个线程不断的从 Queue 中取出一个 HLog，然后把 HLog 中的 Entry 逐个读取出来，放到一个名为 entryBatchQueue 的队列中
- entryBatchQueue 后端有一个名为 ReplicationSourceShipper 的线程，不断从 Queue 中读取 Log Entry，交给 Peer 的 ReplicationEndpoint。ReplicationEndpoint 把这些 Entry 打包成一个 replicationWALEntry 操作，通过 RPC 发送到 Peer 集群的某个 RegionServer 上。对应 Peer 集群的 RegionServer 把 replicateWALEntry 解析成若干个 Batch 操作，并调用 batch 接口执行。待 RPC 调用成功后，ReplicationSourceShipper 会更新最近一次成功复制的 HLog Position 到 ZK，以便 RegionServer 重启后，下次能找到最新的 Position 开始复制

一个 Peer 可能存在多个 walGroupQueue，因为现在 RegionServer 为了实现更高的吞吐量允许同时写多个 WAL，同时写的多个 WAL 属于独立的 Group，所以在一个 Peer 内为每个 Group 设置一个 walGroupQueue。一种常见的场景是，每个业务设置一个独立的 namespace，然后每个 namespace 写自己独立的 WAL，不同的 WAL Group 通过不同的复制线程去推，这样如果某个业务复制阻塞了并不会影响其他的业务，因为不同的 namespace 产生的 HLog 会分到不同的 walGroupQueue。

复制的过程中有两个重要的信息存放在 ZK 上：
- Peer 相关信息，/hbase/replication/peers 目录下存放了 Peer 相关信息
- Peer 和 HLog 推送关系，/hbase/replication/rs 目录下记录了 Peer 的 HLog，以及 HLog 推送的 Postion

### 串行复制
非串行复制的情况下，如果 Region 在复制的时候发生了迁移，则可能导致两个 RegionServer 都会为 Peer 开辟一个复制线程，这样会带来几个问题：
- 源集群中写数据顺序和 Peer 集群中的执行顺序不一致
- 在极端情况下由于数据写入执行顺序乱序导致数据不一致(Delete 数据先于 Put 数据到达，导致 Put 数据不会被清除掉)

非串形复制的根本原因在于 Region 从一个 RegionServer 移动到另外一个 RegionServer 的过程中，Region的数据会分散在两个 RegionServer 的 HLog 上，而两个 RegionServer 完全独立地推送各自的 HLog，从而导致同一个 Region 的数据并行写入 Peer 集群。一个简单的解决思路是将 Region 的数据按照 Region 移动发生的时间切分为 N 段，在每段复制之前需要保证前面所有段的数据已经复制完成即可解决这个问题。

HBase 社区版根据这种思想提供了实现，包含三个概念：
- Barrier：每一次 Region 重新 Assign 到新的 RegionServer 时，新 RegionServer 打开 Region 前能读到的最大 SequenceId，因此每打开一次 Region 就会产生一个新的 Barrier，Region 在打开 N 次之后就会有 N 个 Barrier 把该 Region 的 SequenceId 数轴划分为 N+1 个区间
- LastPushedSequenceId：表示该 Region 最近一次成功推送到 Peer 集群的 HLog 的 SequenceId，事实上每次成功推送一个 Entry 到 Peer 集群后都需要将 LastPushedSequenceId 更新到最新的值
- PendingSequenceId：表示该 Region 当前读到的 HLog 的 SequenceId

HBase 集群值需要对每个 Region 都维护一个 Barrier 列表和 LastPushedSequenceId，就能按照规则确保在上一个区间的数据完全推送之后再推送下一个区间的数据，如果上一个区间的 HLog 还没有完全推送结束，就会休眠一段时间之后再检查一次上一个区间是否推送结束，若推送结束则开始推送本区间的数据

### 同步复制
HBase 的复制一般是异步的，即 HBase 客户端写入数据到主集群之后就返回，然后主集群再异步的把数据依次推送到备份集群。若主集群因意外或者 Bug 无法提供服务时，备份集群的数据是比主集群少的，通过同步复制的方式可以保证备份集群的数据和主集群的数据保持一致。

同步复制的核心思想是 RegionServer 在收到写入请求之后不仅会在主集群上写一份 HLog 日志，还会同时在备份集群上写一份 RemoteWAL 日志，在数据写入时只有主集群上的 HLog 日志和备集群上的 RemoteWAL 都写入成功之后才会返回给客户端。除此之外，主机群到备集群之间还会开启异步复制链路，若主集群的某个 HLog 通过异步复制完全推送到备份集群，那么这个 HLog 在备份集群上对应的 RemoteWAL 则被清理，因此可以认为 RemoteWAL 是指那些已经成功写入主集群但尚未被异步复制成功推送到备份集群的数据，对主集群的每次写入备份集群都不会丢失，当主集群发生故障，只需要回放 RemoteWAL 日志到备份集群，备份集群就可以马上恢复数据为线上提供服务。

为了方便同步复制，主集群和备集群的同步复制状态分为 4 种：
- Active：这种状态的集群将在远程集群上写 RemoteWAL 日志，同时拒绝接收来自其他集群的复制数据。一般情况下同步复制的主集群会处于 Active 状态
- Downgrade Active：这种状态的集群将跳过写 RemoteWAL 流程，同时拒绝接收来自其他集群的复制数据，一般情况下同步复制中的主集群因备份集群不可用卡住后，会被降级为 DA 状态用来满足业务的实时读写
- Standby：这种状态的集群不允许 Peer 内的表被客户端读写，它只接收来自其他集群的复制数据，同时确保不会将本集群中 Peer 内的表数据复制到其他集群上，一般情况下同步复制中的备份集群会处于 Standby 状态
- Node：表示没哟开启同步复制

#### 建立同步复制
建立同步复制分为三步：
- 在主集群和备份集群分别建立一个指向对方集群的同步复制 peer，这是主集群和备份集群的状态默认为 DA
- 通过 transit_peer_sync_replication_state 命令将备份集群的状态从 DA 切换成 Standby
- 将主集群状态从 DA 切换成 Active

#### 备份集群故障处理
当备份集群发生故障时，处理流程如下：
- 将主机群状态从 Active 切换为 DA，此时备份集群已经不可用，所有写到主集群的请求可能会因为写 RemoteWAL 失败而失败，因此需要将主集群从 Active 切换为 DA，这样就不需要写 RemoteWAL 了，保证了业务能正常读写 HBase 集群
- 在确保备份集群恢复后，直接把备份集群状态切换为 S，在备份集群恢复之前的数据通过异步复制同步到了备份集群，恢复之后的数据通过同步复制的方式同步到备份集群
- 将主机群状态从 DA 切换成 Active

#### 主集群故障处理
当主集群发生故障时，处理流程如下：
- 将备份集群的状态从 Standby 切换成 DA，之后备份集群不再接收来自主集群复制过来的数据，在备份集群状态从 Standby 切换成 DA 的过程中会先回放 RemoteWAL 日志，保证主备集群数据一致性后再让业务方把读写流量都切换到备份集群
- 主集群恢复后，虽然业务已经切换到原来的备份集群上，但是原来的主集群还认为自己是 Active 状态
- 由于主集群认为自己是 Active 状态，所有备份集群上的数据不能同步到主集群，这时可以直接将主集群状态切换成 Standby，等原来的备份集群上的数据都同步到主集群之后，两个集群的数据将最终保持一致
- 把备份集群从 DA 切换成 A，继续开启同步复制保持数据一致性


同步复制在数据最终一致性和集群可用性方面比异步复制更加优秀，但是由于需要写一份 RemoteWAL 导致写性能有所下降(13%)、网络带宽以及存储空间更大，且整个过程也更加复杂。

## 备份与恢复
Snapshot 是 HBase 的一个核心功能，使用在线 Snapshot 备份可以实现增量备份和数据迁移。HBase 提供了 4 个在线 Snapshot 备份与恢复的工具：
- snapshot：为表生成一个快照，但并不涉及数据移动，可以在线完成 ```snapshot 'sourceTable', 'snapshotName'```
- restore_snapshot：恢复指定快照，恢复过程会替代原有数据，将表还原到快照点，快照点之后的所有更新将会丢失 ```resotre_snapshot 'snapshotName'
- clone_snapshot：根据快照恢复出一个新表，恢复过程不涉及数据移动，可以在秒级完成 ```clone_snapshot 'snapshotName', 'tableName'```
- ExportSnapshot：可以将一个集群的快照数据迁移到另一个集群，ExportSnapshot 是 HDFS 层面的操作，需要用 MapReduce 进行数据的并行迁移，因此需要两个集群支持 MapReduce。Master 和 RegionServer 不参与迁移过程，因此不会带来额外的内存开销以及 GC 开销。ExportSnapshot 针对 DataNode 需要额外的带宽以及 IO 负责问题设置了 bandwidth 来限制带宽的使用 ```hbase.org.apache.hadoop.hbase.snapshot.ExportSnapshot -snapshot MySnapshot -copy-from hdfs://srv2.8082/hbase -copy-to hdfs://srv1:50070/hbase -mappers 16 -bandwidth 1024```

Snapshot 机制不会拷贝数据，而是类似于原始数据的一份指针，HBase 的 snapshot 只需要为当前表的所有文件分别新建一个引用，对于其他新写入的数据，重新创建一个新文件写入即可。snapshot 流程主要涉及两个步骤：
- 将 MemStore 中的缓存数据 flush 到文件中
- 为所有 HFile 文件分别新建引用指针，这些指针元数据就是 snapshot

HBase 为指定表执行 snapshot 操作时，实际上真正执行 snapshot 的是对应表的所有 Region，HBase 使用二阶段提交(Two-Phase Commit, 2PC)协议来保证这些 Region 在多个不同的 RegionServer 上要么都完成，要么都失败。2PC 一般由一个协调者和多个参与者组成，整个事务提交过程如下：
- prepare 阶段协调者会向所有参与者发送 prepare 命令
- 所有参与者接收到命令，获取相应资源，执行 prepare 操作确认可以执行成功，一般情况下核心工作都是在 prepare 操作中完成
- 返回给协调者 prepared 应答
- 协调者接收到所有参与者返回的 prepared 应答，表示所有参与者都已经准备好提交，然后在本地持久化 committed 状态
- 持久化完成之后进入 commit 阶段，协调者会向所有参与者发送 commit 命令
- 参与者接收到 commit 命令，执行 commit 操作并释放资源，通常 commit 操作都比较简单
- 返回给协调者

prepare 阶段：
- Master 在 ZK 创建一个 /acquired-snapshotname 节点，并在此节点上写入 Snapshot 相关信息
- 所有 RegionServer 检测到这个节点，根据这个节点的 snapshot 信息查看当前 RegionServer 上是否有目标表，如果不存在则忽略，如果存在则遍历目标表中的所有 Region，针对每个 Region 分别执行 snapshot 操作。此时 snapshot 操作的结果并没有写入最终文件夹，而是写入临时文件夹
- RegionServer 执行完成之后会在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex，表示 nodex 节点完成了该 RegionServer 上所有相关 Region 的 snapshot 准备工作

commit 阶段：
- 一旦所有 RegionServer 都完成了 snapshot 的 prepare 工作，即都在 /acquired-snapshotname 节点下新建一个子节点 /acquired-snapshotname/nodex 节点，Master 就认为 Snapshot 的准备工作完全完成，Master 会创建一个新的节点 /reached-snapshotname，表示发送一个 commit 命令给参与的 RegionServer
- 所有 RegionServer 检测到 /reached-snapshotname 节点之后，执行 commit 操作，即将 prepare 阶段生成的结果从临时文件夹移动到最终文件夹即可
- 在 /reached-snapshotname 节点下创建子节点 /reached-snapshotname/nodex，表示节点 nodex 完成 snapshot 工作

abort 阶段：
- 如果在一定时间内 /acquired-snapshotname 没有满足条件，即还有 RegionServer 的准备工作没有完成，Master 认为 snapshot 的准备工作超时，Master 认为 snapshot 的准备工作超时，Master 会新建另一个新节点 /abort-snapshotname，所有 RegionServer 监听到这个命令之后会清理 snapshot 在临时文件夹中生成的结果

在整个 snapshot 过程中，Master 充当了协调者的角色，RegionServer 充当了参与者的角色，Master 和 RegionServer 之间的通信通过 ZK 来完成，同时事务状态也记录在 ZK 的节点上，Master 在高可用的情况下即使发生了切换也会根据 ZK 上的事务状态决定是否继续提交或者回滚

snapshot 在两阶段提交过程中 RegionServer 实现 snapshot 的过程分为三步：
- 将 MemStore 中数据 flush 到 HFile
- 将 region info 元数据(表名、startKey 和 endKey) 记录到 snapshot 文件中
- 将 Region 中所有的 HFile 文件名记录到 snapshot 文件中

Master 会在所有 Region 完成 snapshot 之后执行一个汇总操作，将所有 region snapshot manifest 汇总成一个单独的 manifest 存放在 HDFS 目录 /hbase/.hbase-snapshot/snapshotname/data.menifest，该目录下有三个文件，其中 .snapshotinfo 为 Snapshot 基本信息，包含待 snapshot 的表名称以及 snapshot 名；data.manifest 为 snapshot 执行后生成的元数据信息，即 snapshot 结果信息

clone_snapshot 过程分为六步：
- 预检查，确认当前目标表没有执行 snapshot 以及 restore 等操作，否则直接返回错误
- 在 tmp 文件夹下新建目标表目录并在表目录下新建 .tabledesc 文件，在该文件中写入表 schema 信息
- 新建 region 目录，根据 snapshot manifest 中的信息新建 Region 相关目录以及 HFile 文件
- 将表目录从 tmp 文件夹下移到 HBase Root Loacation
- 修改 hbase:meta 表，将克隆表的 Region 信息添加到 hbase:meta 表中，注意克隆表的 Region 名称和原数据表的 Region 名称并不相同
- 将这些 Region 通过 round-robin 方式均匀分配到整个集群中，并在 ZK 上将克隆表的状态设置为 enabled，正式对外提供服务

clone_snapshot 工具克隆表的过程并不涉及数据的移动，而是使用了 LinkFile 的文件指向了源文件，LinkFile 文件本身没有任何内容，它的所有核心信息都包含在它的文件名中，通过文件名可以定位到原始文件的具体路径

snapshot 实际上是一系列原始表的元数据，主要包括表 schema 信息、原始表的所有 Region 的 regioninfo 信息，如果原始表发生了 Compaction 导致 HFile 文件名发生了变化或者 Region 发生了分裂等，HBase 会在原始表发生 compaction 操作前将原始表数据复制到 archive 目录下再执行 compact，这样 snapshot 对应的元数据就不会失去意义，只不过原始数据不再存放于数据目录下，而是移动到了 archive 目录下


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

### Hbase 操作
#### Schema 操作
HBase 的 Schema 通过 Admin 对象来创建；在修改列族之前，表必须是 disabled；对表或者列族的修改需要到下一次主合并并且 StoreFile 重写才能生效。
```java
Configuration conf = HBaseConfiguration.create();
Connnection conn = ConnectionFactory.createConnection(conf);

Admin admin = conn.getAdmin();

TableName table = TableName.valueOf("table_name");
admin.disableTable(table);

ColumnFamilyDescriptor descriptor = ColumnFamilyDescriptorBuilder
	.newBuilder("column_family".getBytes())
	.setMaxVersions(1)
	.build();
admin.addColumnFamily(table, descriptor);
admin.modifyColumnFamily(table, descriptor);

admin.enableTable(table);
```
#### 数据操作
#### Get
默认在 Get 操作没有显式指定版本的时候的到的是最新版本的数据，可以在 Get 的时候设置版本相关参数：
- Get.setMaxVersion() - 设定返回多个版本的数据
- Get.setTimeRange() - 设置返回指定版本的数据
```shell
get '<table_name>', '<row_key>'
```
```java
Get get = new Get()
```
#### Put
Put 操作田间一行数据到表(行键不存在)或者更新已经存在的行(行键已存在)；Put 操作通过 Table.put 或者 Table.batch 完成。

每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。
```shell
```
```java
```
#### Scan
Scan 可以在指定属性下迭代多行。
```shell
```
```java
```
#### Delete
Delete 通过 Table.delete 来删除表中的一行。

HBase 不会立马修改数据，因此是通过创建名为“墓碑”的标记在主合并的时候连同数据一起被清除。

删除操作也可以指定版本，如果没有指定则删除所有的版本。
```shell
```
```java
```

### Ref
- [HTAP(Hybrid Transaction and Analytical Processing)]()
- [空间局部性]()
- [时间局部性]()