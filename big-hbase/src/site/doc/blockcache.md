## BlockCache

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