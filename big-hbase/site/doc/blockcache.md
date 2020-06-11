## BlockCache

HBase 设计了缓存结构 BlockCache 用以提升读性能，客户但在读取 Block 时首先检查 BlockCache 中是否存在，如果存在则直接从 BlockCache 中读取，否则从 HFile 中加载并将 Block 缓存在 BlockCache 中。Block 是 HBase 数据读写的最小单元，即数据从 HFile 中读取都是以 Block 为最小单元执行的。

BlockCache 缓存对象是一系列 Block 块，一个 Block 默认为 64K，由物理上相邻的多个 K-V 数据组成。BlockCache 同时利用了[空间局部性]()和[时间局部性]()原理，即最近将读取的数据很可能与当前读到的数据在地址上时临近的，以及一个数据正在被访问，那么近期它还可能被再次访问。当前 BlockCache 主要有两种实现：LRUBlockCache 和 BucketCache。

BlockCache 是 RegionServer 级别的，一个 RegionServer 只有一个 BlockCache，RegionServer 在启动的时候完成 BlocCache 的初始化：
```java
// HRegionServer 构造函数中初始化 BlockCache

// no need to instantiate block cache and mob file cache when master not carry table
if (!isMasterNotCarryTable) {
    blockCache = BlockCacheFactory.createBlockCache(conf);
    mobFileCache = new MobFileCache(conf);
}
```
HBase 提供了多种 BlockCache 接口的实现类，根据不同的应用场景可以指定不同的 BlockCache 实现。
```
思考：
    1. BlockCache 时 RegionServer 级别，是不是粒度较大？为何不设计成 HTable 级别？
    2. 缓存淘汰、内存管理、缓存容量、缓存监控实现？
```

### LruBlockCache

LruBlockCache 是 HBase 默认的 BlockCache 机制，将所有数据都放入 JVM 中管理。LruBlockCache 使用 ConcurrentHashMap 管理 blockKey 到 Block 的映射，查询时只需要根据 blockKey 就可以获取到对应的 Block。

LruBlockCache 

#### 写缓存

LruBlockCache 使用三级缓存机制，即存储缓存的数据分为三个等级：single, multi 和 memory。Block 在存入 LruCacheBlock 前会包装成 LruCachedBlock，LruCachedBlock 在创建的时候会根据 imMemory 参数来设置优先级，如果为 true 则表示需要常驻内存，优先级为 ```BlockPriority.MEMORY```，否则为 ```BlockPriority.SINGLE```。

```java
```


#### 读缓存

#### 缓存淘汰



LruBlockCache 使用三级缓存机制，将整个 BlockCache 分为三部分：single、multi、in-memory，分别占到 25%, 50%, 25%。Block 从 HFile 中加载出来后放入 single，如果后续有多个请求访问到这个 Block，则将该 Block 移到 multi，in-memory 区表示数据可以常驻内存。in-memory 区用于存放访问频繁、量小的数据，比如元数据。在建表的时候设置列簇属性 IN_MEMORY=true 之后该列簇的 Block 在磁盘中加载出来后会直接放入 in-memory 区，但是数据写入时并不会写入 in-memory 区，而是和其他 BlockCache 区一样，只有在加载的时候才会放入，进入 in-memory 区的 Block 并不意味着一直存在于该区域，在空间不足的情况下依然会基于 LRU 淘汰算法淘汰最近不活跃的一些 Block。HBase 元数据(hbase:meta, hbase:namespace 等)都存放在 in-memory 区，因此对于很多业务表来说，设置数据属性 IN_MEMEORY=ture 时需要注意可能会由于空间不足而导致 hbase:meta 等元数据被淘汰，从而会严重影响业务性能

LruBlockCache 使用 LRU 算法淘汰数据，当 BlockCache 中 Block 总量达到阈值之后就会自动启动淘汰机制，最近最少使用的 Block 就会被淘汰。LruBlockCache 每次将数据放入 Map 后都会检查 BlockCache 容量是否到达阈值，如果到达阈值则会唤醒淘汰线程对 Map 中的 Block 进行淘汰，BlockCache 设置了 3 个 MinMaxPriorityQueue 分别对应缓存的三层，每个队列中的元素按照最近最少使用原则释放 Block 的内存。

LruBlockCache 使用堆内存作为缓存，当 Block 长时间在缓存中存活后被淘汰，此时会有触发 Full GC 的风险。

### BucketCache

BucketCache 通过不同配置方式可以工作在三种模式下：heap, offheap, file。其中 heap 模式表示 Bucket 是从堆内存中分配的，offheap 模式表示 Bucket 是从直接内存中分配的，file 模式表示使用存储介质来缓存 Block。

BucketCache 是以 Bucket 为单位的，BucketCache 会申请多种固定大小的 Bucket，每种 Bucket 一种指定 blockSize 的 Block。

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

### CombinedBlockCache