## Store

Store 对应着  Region 中的列簇，每个 Region 中都有和列簇相同数量的 Store，每个 Store 维护着一个 MemStore 和多个 StoreFile (HFile)。

HBase 采用 LSM 树架构，数据写入时先写入 MemStore，当数据写到一定容量时触发 flush 操作将 MemStore 刷到磁盘形成 HFile 文件。

### MemStore

所有写入的数据都会首先存储在 MemStore 中，当 Region 触发 flush 操作时，MemStore 中的数据会批量写入磁盘，生成 StoreFile。

MemStore 使用跳跃表结构保证在有序的情况下保证查询、插入操作的时间复杂度为 O(logN)。为了防止 MemStore 在 flush 之后产生过多的内存碎片，HBase 借鉴 TLAB (Thread-Local Allocation Buffer) 机制引入了 `MemStoreLAB` 来保存写入的数据，。

- 数据写入 MemStore 时会将写入的 KeyValue 复制到 Chunk 中并返回新的 Cell 对象引用 Chunk 中的 KeyValue
- 返回的 Cell 对象会写入跳跃表结构中保证写入数据的顺序，新生成的 Cell 对象较写入的 KeyValue 对象小得多

`MemStoreLAB` 将零碎的内存变成紧凑的内存块，为了防止 MemStoreLAB 中的 Chunk 回收，HBase 在每个 RegionServer 上引入全局 `MemStoreChunkPool` 来全局的管理 Chunk 的生成和回收：

- `MemStoreLAB` 对象的 Chunk 写满后需要向 `MemStoreChunkPool` 申请新的 Chunk
- `MemStoreChunkPool` 在处理 Chunk 申请时首先确定是否有空闲的 Chunk，有则直接返回空闲的 Chunk，否则创建新的 Chunk 返回
- `Chunk` 没有被使用时并不会被 JVM 回收，而是由 `MemStoreChunkPool` 管理

### CompactingMemStore

`ConcurrentSkipList` 结构并不是内存友好的，其每个节点除了数据对象外，还包含过多的索引对象占用了额外内存。

HBase 引入了 In-Memory 机制来减少额外的内存占用，其核心原理是将 MemStore 分成可变的 `MutalbeSegment` 和不可变的 `ImmutableSegment`，并且将不可变的 `ImmutableSegment` 中的 `ConcurrentSkipList` 结构转换成内存友好的 `CellArrayInmuutableSegment` 或者 `CellImmutableSegment`。

In-Memory Compaction 机制是通过 `CompactingMemStore` 完成，



```
// todo
```



HBase 中 MSLAB 功能默认是开启的，可以通过参数 ```hbase.hregion.memstore.mslab.chunksize``` 设置 ChunkSize 的大小，默认是 2M，建议保持默认值；Chunk Pool 功能默认是关闭的，通过参数 ```hbase.hregion.memstore.chunkpool.maxsize``` 为大于 0 的值才能开启，默认是 0，该值得取值范围为 [0,1] 表示整个 MemStore 分配给 Chunk Pool 的总大小；参数 ```hbase.hregion.memstore.chunkpool.initialsize``` 取值为 [0,1] 表示初始化时申请多少个 Chunk 放到 Chunk Pool 里面，默认是 0



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

### Flush

`MemStore` 刷盘的最小单元是 Region，也就是说在触发刷盘时当前 Region  的所有 `MemStore` 都需要将数据刷盘到 HDFS。只要满足下列任意情况都会触发 `MemStore` 刷盘：

- `MemStore` 大小超过 `hbase.hregion.memstore` 配置的值，则当前 Region 的所有 MemStore 都会刷盘
- RegionServer 中所有的 MemStore 大小之和超过 `hbase.regionserver.global.memstore.upperLimit` 配置的值，则所有的 Region 按照 MemStore 的使用量降序刷盘，直到所有的 MemStore 之和降至 `hbase.regionserver.global.memstore.lowerLimit`
- 当 RegionServer 的 WAL 的条目达到了 `hbase.regionserver.max.logs`，则所有 Region 的 MemStore 按照时间刷盘，直到 WAL 的数量降至 `hbase.regionserver.max.logs`

HBase 采用了类似于二阶段提交的方式将整个 flush 过程分为了三个阶段：

- prepare 阶段：遍历当前 Region 中所有 MemStore，将 MemStore 中当前数据集 CellSkipListSet(采用 ConcurrentSkipListMap) 做一个快照 snapshot，然后再新建一个 CellSkipListMap 接收新的数据写入。prepare 阶段需要添加 updateLock 对写请求阻塞，结束之后会释放该锁，持锁时间很短
- flush 阶段：遍历所有 MemStore，将 prepare 阶段生成的 snapshot 持久化为临时文件，放入目录 .tmp 下，这个过程涉及到磁盘 IO，因此比较耗时
- commit 阶段：遍历所有的 MemStore，将 flush 阶段生成的临时文件移到指定的 ColumFamily 目录下，针对 HFile 生成对应的 storefile 和 Reader，把 storefile 添加到 Store 的 storefile 列表中，最后再清空 prepare 阶段生成的 snapshot

通过 shell 命令 flush 'tablename' 或者 flush 'regionname' 分别对一个表或者一个 Region 进行 flush

```

```



### StoreFile

HBase 执行 flush 操作后将内存中的数据按照特定格式写入 HFile 文件。MemStore 中 KV 在 flush 成 HFile 时首先构建 Scanned Block 部分，即 KV 写入之后首先构建 Data Block 并依次写入文件，形成 Data Block 的过程中也会依次构建形成 Leaf index Block、Bloom Block 并依次写入文件。一旦 MemStore 中所有 KV 都写入完成，Scanned Block 部分就构建完成。



StoreFile 是 HBase 对数据持久化的抽象，HFile 是 HBase 将数据持久化在 HDFS 上的数据格式。

使用 HBase 提供的工具 `hbase file` 可以查看 HFile 的内容

```
${HBASAE_HOME}/bin/hbase file -v -f hdfs://path_of_hfile
```



#### Block



HBase 中 MemStore 的大小达到阈值之后会触发 flush，之后每个 MemStore 生成一个 StoreFile 以 HFile 的格式存储在 HDFS 上。HFile 中存储的是有序的 K-V 对，其中的 K 和 V 都是字节数组。

从逻辑上看 HFile 主要分为 4 个部分：

- ```ScannedBlock```：包括 DataBlock, LeafIndexBlock, BloomBlock。顺序扫描 HFile 时所有 Block 都


MmeStore 中数据落盘之后会形成一个文件写入 HDFS，这个文件称为 HFile，HFile 文件主要分为 4 个部分：

- ```Scanned Block```：表示顺序扫描 HFile 时所有的数据块将会被读取，包括 3 中数据块：Data Block、Leaf Index Block 以及 Bloom Block。其中 Data Block 中存储用户的 k-v 数据，Leaf Index Block 存储索引树的叶子节点数据，Bloom Block 中存储布隆过滤器相关数据
- ```Non-canned Block```：表示在 HFile 顺序扫描的时候数据不会被读取，主要包括 Meta Block 和 Intermediate Level Data Index Block 两部分
- ```Load-on-open```：这部分数据会在 RegionServer 打开 HFile 时直接加载到内存中，包括 FileInfo、布隆过滤器 MetaBlock、Root Data Index 和 Meta IndexBlock
- ```Trailer```：这部分主要记录了 HFile 的版本信息，其他各个部分的偏移值和寻址信息

HFile 文件由各种不同类型的 Block 构成，这些 Block 拥有相同的数据结构，Block 的大小可以在创建表列簇的时候通过参数 blocksize => '65535' 指定，默认 64K。通常来讲较大的 Block 更利于顺序查询，较小的 Block 有利于随机查询。

HFile 中所有 Block 都拥有相同的数据结构，HBase 将所有 Block 统一抽象为 HFileBlock，主要包含两部分：BlockHeader 和 BlockData。BlockHeader 主要存储 Block 相关元数据，BlockData 用于存储具体数据。

BlockHeader 元数据中的字段 BlockType 表示 Block 的类型，总共有 8 中类型：

- ```Trailer Block```：记录 HFile 基本信息，文件中各个部分的偏移量和寻址信息
- ```Meta Block```：存储布隆过滤器相关元数据信息
- ```Data Block```：存储 k-v 数据
- ```Root Index```：HFile 索引树根索引
- ```Intermediate Level Index```：HFile 索引树中间层级索引
- ```Leaf Level Index```：HFile 索引树叶子索引
- ```Bloom Meta Block```存储 Bloom 相关元数据
- ```Bloom Block```：存储 Bloom 相关数据

Trailer Block 主要记录了 HFile 的版本信息、各个部分的偏移值和寻址信息。RegionServer 在打开 HFile 时会加载所有 HFile 的 Trailer 部分以及 load-on-open 部分到内存中，具体步骤为：

- 加载 HFile version 版本信息，HBase 中 version 包含 majorVersion 和 minorVersion 两部分，前者决定了 HFile 的主版本，后者在主版本确定的基础上决定是否支持一些微小修正，不同的版本使用不同的文件解析器对 HFile 进行行读取解析
- HBase 会根据 version 信息计算 Trailer Block 的大小，不同 version 的 Trailer Block 大小不同，再根据 Trailer Block 大小加载整个 HFile Trailer Block 到内存中
- 根据 Trailer Block 中 LoadOnOpenDataOffset 和 LoadOnOpenDataSize 将 load-on-open Section 的数据全部加载到内存中。load-on-open 部分主要包括 FileInfo 模块、RootDataIndex 模块以及布隆过滤器模块

Data Block 是 HBase 中文件读取的最小单元，Data Block 中主要存储 k-v 数据。K-V 数据由 4 部分构成：

- keyLength
- valueLength
- key：由 rowkey、columnFamily、columnQualifier、timestamp、keyType 组成
- value

由于任意的数据都需要存储 rowkey、columnFamily、columnQulifier、timestamp、keyType 因此会占用额外的存储空间，所以在 HBase 表结构设计时需要使用尽可能短的命名

HBase 是基于 LSM 树结构构件的数据库系统，数序首先写入内存，然后异步 flush 到磁盘形成文件。这种架构对写入友好，而对数据读取并不友好，随着数据的不断写入，系统会生成大量文件，在根据 key 查询数据时，理论上需要将所有的文件遍历以便，这种遍历是非常低效的。

使用布隆过滤器可以对数据读取进行相应的优化，对于给定的 key，经过布隆过滤器处理就可以知道该 HFile 中是否存在待检索 key，如果不存在就不需要遍历查找该文件，这样就可以减少实际 IO 次数，提高随机读取性能。

HBase 会为每个 HFile 分配对应的位数组，K-V 在写入 HFile 时会先对 Key 经过多个 hash 函数的映射，映射后将对应的数组位置 1，根据 key 查找数据时使用相同的 hash 函数对 key 进行映射，如果对应的数组位上存在 0 则表示查询的数据一定不再该文件中，如果全部为 1 则表示该文件中可能存在查询的数据，需要对文件遍历查找

为了避免因为 K-V 数据量较大导致位数组较大从而占用过多内存，HFile 将位数组根据 key 拆分成了多个独立的位数组，一部分连续的 key 使用一个位数组，这样一个 HFile 中就会包含多个位数组，根据 key 查询数据时，首先定位到具体的位数组，只需要加载此位数组到内存进行过滤即可，从而降低了内存开销。

每个位数组对应 HFile 中一个 Bloom Block，HFile 包含 Bloom Index Block 用于定位对应的位数组。每个 HFile 仅有一个 Bloom Index Block 数据块，位于 load-on-open 部分，Bloom Index Block 由两部分内容构成：HFile 中布隆过滤器的元数据基本信息和指向 Bloom Block 的索引信息。

Bloom Index Block 结构中 TotalByteSize 表示位数组大小，NumChunks 表示 Bloom Block 的个数，HashCount 表示 hash 函数的类型，TotalKeyCount 表示布隆过滤器当前已经包含的 key 的数目，TotalMaxKeys 表示布隆过滤器当前最多包含的 key 数目。

Bloom Index Entry 对应每一个 Bloom Block 的索引项，作为索引分别指向 sanned block 部分的 Bloom Block，Bloom Block 中实际存储了对应为位数组。Bloom Block Entry 中的 BlockKey 表示该 Index Entry 指向的 Bloom Block 中第一个执行 Hash 映射的 key，BlockOffset 表示对应 Bloom Block 在 HFile 中的偏移量。

一次 get 强求根据布隆过滤器进行过滤查找需要指向三个步骤：

- 根据带查找 key 在 Bloom Index Block 所有的索引项中根据 Block Key 进行二分查找，定位到对应的 Bloom Index Entry
- 根据 Bloom Index Entry 中 BlockOffset 以及 BlockOndiskSize 加载该 key 对应的位数组
- 对 key 进行 hash 映射，根据映射的结果在位数组中查看是否所有位都为 1，如果不是则表示该文件中肯定不存在该 key，否则有可能存在

根据索引层级不同，HFile 中索引结构分为两种：single-level 和 multi-level，当 HFile 文件越来越大，Data Block 越来越多，索引数据也越来越大，已经无法全部加载到内存中，多级索引可以只加载部分索引，从而降低内存使用空间。

Index Block 有两类：Root Index Block 和 NonRoot Index Block，NoRoot Index Block 又分为 Intermediate Index Block 和 Leaf Index Block两种。HFile 索引是树状结构，Root Index Block 表示索引树根结点，Intermediate Index Block 表示中间结点，Leaf Index Block 表示叶子结点，叶子结点直接指向实际的 Data Block。Root Index Block 位于 load-on-open 部分，会在 RegionServer 打开 HFile 时加载到内存中，Intermediate Index Block 位于 Non scanned block 部分，Leaf Index Block 位于 sanned block 部分。

对于 Data Block，刚开始时 HFile 数据量较小，索引采用单层结构，只有 Root Index 一层索引直接指向 Data Block，当数据量增加使得 Root Index Block 超过阈值则胡由单层结构分裂成多层结构，根结点指向叶子节点，叶子节点指向 Data Block，当数据再次增加则会到值索引层级变为三层。

Root Index Block 表示索引树根结点索引块，即可以作为 Bloom Block 的直接索引，也可以作为 Data Block 多级索引树的根索引。对于单层和多级两种索引结构，对应的 Root Index Block 结构略有不同，单层索引是多级索引结构的一种简化场景。

Index Entry 表示具体的索引对象，每个索引对象由 3 个字段组成：

- Block Offset 表示索引指向 Data Block 的偏移量
- BlockDataSize 表示索引指向 DataBlock 在磁盘的大小
- BlockKey 表示索引指向的 Data Block 中的第一个 Key

除了 Index Entry 外，还有三个字段用来记录 MidKey 的相关信息，这些信息用于在对 HFile 进行 split 操作时，快速定位 HFile 的切分点位置。单层索引结构和多层索引结构的区别在于缺少 MidKey 相关的字段

Root Index Block 位于整个 HFile 的 load-on-open 部分，因此会在 Region Server 打开 HFile 时直接加载到内存中，Trailer Block 中的 DataIndexCount 字段记录了 Root Index Block 中 Index Entry 的个数。

当 HFile 中 Data Block 越来越多，单层结构的根索引会不断膨胀，超过一定阈值之后就会分裂为多级结构的索引结构。多级结构中根结点是 Root Index Block，而索引树的中间层节点和叶子节点在 HBase 中存储为 NonRoot Index Block，中间节点和叶子节点都具有相同的结构：

- NumEntries
- EntryOffset
- IndexEntry

和 Root Index Block 相同，NonRoot Index Block 中最核心的字段也是 Index Entry，用于指向叶子结点块或者 Data Block，不同的是 NonRoot Index Block 结构中增加了 Entry Offset 字段表示 Index Entry 在该 Block 中相对第一个 Index Entry 的偏移量，用于实现 Block 内的二分查找。通过这种机制，所有非根结点索引块在其内部定位一个 Key 的具体索引并不是通过遍历实现，而是使用二分查找算法，更加高效快速的定位到待查找的 key

HBase 提供命令行工具来查看 HFile 文件的基本信息，在 bin 目录下执行 ```hbase hfile``` 命令可以看到 HFile 的相关元数据信息

```shell

```

HFile 的 V3 版本中增加了对 cell 的标签功能，cell 标签为其他与安全相关的功能提供了实现框架。HFile 中关于 cell 有两个地方：

- File Info Block 新增了两个信息：MAX_TAGS_LEN 和 TAGS_COMPRESSED，前者表示单个 cell 中存储标签的最大字节数，后者是一个 boolean 类型的值，表示是否针对标签进行压缩处理
- Data Block 中每个 K-V 结构新增三个标签相关信息：Tags Length(2 个字节)、Tag Type(1 个字节) 和 Tags Bytes。Tags Length 表示标签的数据大小，Tags Type 表示标签类型(标签类型有 ACL_TAG_TYPE、VISIBILITY_TAG_TYPE 等)，Tags bytes 表示具体的标签内容。



https://blog.csdn.net/u011598442/article/details/105571034