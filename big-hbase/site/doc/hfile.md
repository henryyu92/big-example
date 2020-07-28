## HFile

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