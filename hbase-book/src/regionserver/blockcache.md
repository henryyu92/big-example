## BlockCache

`BlockCache` 是 HBase 用于提升读性能的缓存结构，RegionServer 读取 Block 时会首先检查该 Block 是否存在于 `BlockCache`，如果存在则直接读取出来，否则到 HFile 中加载对应的 Block 并缓存在 `BlockCache` 中。

`BlockCache` 中缓存的是 `Block`，是 HBase 中最小的数据读取单元，也就是数据从 HFile 中读取都是以 Block 为单位进行的。每个 Block 由物理上相邻的 K-V 数据组成，默认大小为 64K，Block 是组成 HFile 的基本单位。

`BlockCache` 是 RegionServer 级别的，也就是说每个 RegionServer 中只包含一个 `BlockCache`，并且在 RegionServer 启动时完成初始化。

```
思考：
    1. BlockCache 是 RegionServer 级别，是不是粒度较大？为何不设计成 Region 级别？
    2. 缓存淘汰、内存管理、缓存容量、缓存监控实现？
```

HBase 提供了两种不同的 `BlockCache` 实现：

- `LruBlockCache` ：缓存数据在 JVM 堆内存中，可能会导致 Full GC
-  `BucketCache`：将数据缓存在堆外，通常在文件中

// todo

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



LruBlockCache 采用 lru 算法淘汰缓存，LruBlockCache 在后台运行了 Daemon 线程用于淘汰缓存，线程遍历整个
```java
if (evictionThread) {
  this.evictionThread = new EvictionThread(this);
  this.evictionThread.start(); // FindBugs SC_START_IN_CTOR
} else {
  this.evictionThread = null;
}
```


### BucketCache

`BucketCache` 通常和 `LruBlockCache` 共同作用，并且由 `CombinedBlockCache` 管理，其工作方式是将 `Index Block` 和 `Bloom Block` 缓存在 `LruBlockCache` ，而将 `Data Block` 缓存在 `BucketCache`。

BucketCache 没有使用 JVM 内存管理算法来管理缓存，因此大大降低了因为出现大量内存碎片导致 Full GC 发生的风险。

 `BucketCache` 通过不同配置方式可以工作在三种模式下：

- `offheap`：使用堆外内存缓存 Block
-  `file`：使用文件系统来缓存 Block
- `mmaped file`： 使用文件映射的方式将 Block 缓存在文件中

通过在配置文件 `hbase.site.xml` 中设置参数 `hbase.bucketcache.ioengine` 可以配置 `BucketCache` 在三种模式之间切换：

```xml
<!-- 配置用于启用 CombinedBlockCache -->
<property>
  <name>hbase.bucketcache.ioengine</name>
  <!-- file 模式 -->
  <value>files:PATH_TO_FILE1,PATH_TO_FILE2</value>
  <!-- 文件内存映射 -->
  <value>mmap:PATH_TO_FILE</value>
  <!-- offheap 模式 -->
  <value>offheap</value>
</property>
<property>
  <name>hfile.block.cache.size</name>
  <value>0.2</value>
</property>
<!-- 配置 Bucket 的大小 -->
<property>
  <name>hbase.bucketcache.sizes</name>
  <value>4196</value>
</property>
```

`BucketCache` 使用 `BucketAllocator` 来管理 Bucket，并且通过 `ramCache` 和 `backingMap` 来判断 Block 是否在缓存中。

`BucketAllocator` 将缓存



```java

```

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

- 将 Block 封装成 `RamQueueEntry` 缓存到 `RAMCache` 中，并且将 `RamQueueEntry` 加入到对应的阻塞队列中
- `WriteThread` 以阻塞的方式从队列中获取 `RamQueueEntry`，并由 `BucketAllocator` 分配内存空间，BucketCache 会启动多个 writeThread，每个 writeThread 对应一个阻塞队列

- WriteThread 将 Block 以及分配好的物理地址偏移量传给 IOEngine 模块，执行具体的写入操作
- 写入成功后，将 blockKey 与对应物理内存偏移量的映射关系写入 BackingMap 中，方便后续查找时根据 blockKey 直接定位

Block 缓存读取流程：

- 首先从 RAMCache 中查找，对于还没有来得及写入 Bucket 的缓存 Block，一定存储在 RAMCache 中
- 如果在 RAMCache 中没有找到，再根据 blockKey 在 BackingMap 中找到对应的物理偏移地址量 offset
- 根据物理偏移地址 offset 直接从内存中查找对应的 Block 数据

### Compressed BlockCache

`Compressed BlockCache` 是指数据是以磁盘存储的格式缓存，而不需要在缓存前将磁盘中读取的数据进行解压缩以及解密。

启用 `Compressed BlockCache` 会提高吞吐量并且降低平均延时，但是会增加 CPU 的负载并且增加垃圾回收频率。

在配置文件 `hbase-site.xml` 中配置参数 `hbase.blcok.data.cachecompressed` 为 true 就可以开启 `Compressed BlockCache` 机制：

```xml
<property>
    <name>hbase.block.data.cachecompressed</name>
    <value>true</value>
</property>
```

### BlockCache 配置



- `hbase.bucketcache.acceptfactor`：淘汰缓存数据前缓存容量的占用比例，默认是 0.95
- `hbase.bucketcache.minfactor`：

