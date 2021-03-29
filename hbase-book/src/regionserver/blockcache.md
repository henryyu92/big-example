## BlockCache

BlockCache 是 HBase 系统中用于提升读性能的缓存，每个 RegionServer 中只包含一个 `BlockCache`。

RegionServer 查询时首先检查 `BlockCache` 中是否存在，如果存在则直接读取，否则需要从 HFile 中加载对应的 Block 并且缓存到 `BlockCache` 中。

`BlockCache` 以 Block 为缓存单位，每个 Block 由物理上相邻的 K-V 数据组成，默认大小为 64K。

HBase 提供了两种不同的 `BlockCache` 实现：`LruBlockCache` 和 `BucketCache`。`LruBlockCache` 缓存的所有数据都在 JVM 堆内存中；`BucketCache` 则将数据缓存在堆外，通常在文件中。

```
思考：
    1. BlockCache 是 RegionServer 级别，是不是粒度较大？为何不设计成 Region 级别？
    2. 缓存淘汰、内存管理、缓存容量、缓存监控实现？
```

### LruBlockCache

LruBlockCache 是 HBase 默认的缓存算法，将所有数据都放入 JVM 中管理。LruBlockCache 使用 ConcurrentHashMap 管理 blockKey 到 Block 的映射，查询时只需要根据 blockKey 就可以获取到对应的 Block。

LruBlockCache 使用三级缓存机制，：

- `Single access priority`：Block 从 HDFS 加载时拥有的优先级，在 Block 进行 LRU 策略移除时优先移除此优先级的 Block，`Single` 级别的缓存占缓存总量的 25%
- `Multi access priority`：访问 `Single access priority` 中的 Block 时，则调整 Block 到当前优先级，`Multi` 级别的缓存占缓存总量的 50%
- `In-Memory access priority`：如果列簇设置为 `in-memory` ，则不管 Block 访问了多少次都会设置为当前优先级，在缓存根据 LRU 策略移除 Block 时此优先级的 Block 最后被移除，`In-Memory` 级别的缓存占缓存总量的 25%

HRegion 在启动时默任启用了 `LruBlockCache`，缓存的总量由参数 `hfile.block.cache.size`  设置，表示占堆内存的比例，默认是 40%。

```

```

`LruBlockCache` 中存储的 Block 从 HFile 中加载，因此除了缓存 `Data Block` 之外还会存储其他的信息：

- `hbase:meta` 和 `hbase:namespace` 等元数据信息强制存储在 BlockCache 中，并且默认是 `In-Memory` 级别
- `Index Block` 是 HFile 中的索引信息，利用索引信息可以不加载整个 HFile 而读取到数据 
- `Bloom Block`  是块的布隆过滤器信息，可以快速判断查找的数据是否不在当前块

`LruBlockCache` 使用堆内存作为缓存，频繁的触发 lru 淘汰策略移除缓存的 Block 会导致大量的垃圾回收，因此对于特定的应用场景需要关闭缓存功能：

- 完全随即读取，这种情况下缓存的命中率几乎为 0

`BlockCache` 总是会缓存 `Index Block` 和 `Bloom Block`，因此在访问随即数据时可以只缓存元数据信息而不缓存数据，此时只需要在创建表的时候禁用列簇的 BlockCache 功能：

```java
ColumnFamilyDescriptorBuilder.setBlockCacheEnabled(false);
```



### BucketCache

`BucketCache` 通常和 `LruBlockCache` 共同作用，并且由 `CombinedBlockCache` 管理。



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

