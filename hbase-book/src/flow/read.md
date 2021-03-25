## 读流程

客户端从 HBase 中读取数据的流程相较于写入数据的流程来说更加复杂，其整个过程中涉及多个 Region，多个缓存以及多个 HFile。另外更新操作是插入了新的以时间戳为版本数据，删除操作是插入了一条标记为 delete 标签的数据，因此在读取数据时还需要根据版本以及删除标签进行过滤。

HBase 提供 get 和 scan 两种方式读取数据，其中 get 是 scan 的一种特殊形式。HBase 的整个数据读取流程可以分为 ```Region 定位``` 和 ```数据查询``` 两部分。

### Region 定位

HBase 中表的数据是由多个 Region 构成，这些 Region 分布在整个集群的 RegionServer 上，因此客户端在读取数据时首先需要确定数据所在的 RegionServer，然后才能到对应的 RegionServer 上读取数据。

HBase 设计了 ```hbase:meta``` 表专门用来存放整个集群所有的 Region 信息，在 HBase Shell 环境下使用 ```describe 'hbase:meta'``` 命令可以看到整个表的结构：
```shell
describe 'hbase:meta'

# 
```
```hbase:meta``` 表只有 ```info``` 这个列簇，这个列簇中包含 4 列：
- ```info:regioninfo```：主要存储 EncodedName, RegionName, StartRow, StopRow
- ```info:seqnumDuringOpen```：主要存储 Region 打开时的 sequenceId
- ```info:server```：主要存储 Region 对应的 RegionServer
- ```info:serverstartcode```：主要存储 Region 对应的 RegionServer 的启动 TimeStamp

```hbase:meta``` 表中每一行数据记录一个 Region 的信息，其中 rowkey 由 TableName(表名)、StartRow(Region 起始 rowkey)、Timestamp(Region 创建时间戳)、EncodedName(字段 MD5)拼接而成。HBase 保证 ```hbase:meta``` 表始终只有一个 Region，这样可以保证 meta 表操作的原子性。


HBase 客户端缓存 ```hbase:meta``` 信息到 MetaCache，客户端在根据 rowkey 查询数据时首先会到 MetaCache 中查找 rowkey 对应的 Region 信息，如果查找不到或者根据查到的 Region 信息到对应的 RegionServer 上不能找到数据，则会到 ```hbase:meta``` 表中使用 ReversedScan 获取新的 Region 信息，然后将 ```(regionStartRow, region)``` 二元组信息存放在 MetaCache 中。
```
# todo

# hbase:meta 使用的是 ReversedScan 而不是正向的扫描？
```

```hbase:meta``` 表也是存储在 RegionServer 上，其所在的 RegionServer 的信息存储在 ZK 上，客户端获取 ```hbase:meata``` 表中的数据之前需要从 ZK 上获取到 ```hbase:meta``` 表所在的 RegionServer。
```shell
get /

# 
```

为了避免 scan 的数据量过大导致网络带宽被占用和客户端 OOM 的风险，客户端将 scan 操作分解为多次 RPC 请求，每个 RPC 请求为一次 next 请求，next 请求返回的一批数据会缓存到客户端，缓存的数据行数可以由 scan 参数 ```caching``` 设定，默认值为 ```Integet.MAX_VALUE```。为了防止列的数据量太大，HBase 的 scan 操作还可以通过参数 ```batch``` 设定每个 next 请求返回的列数；HBase 的 scan 操作还有 ```maxResultSize``` 参数用于设定每个 next 请求的数据大小，默认值 2G：
```java
Scan scan = new Scan()
        .withStartRow("startRow".getBytes())
        .withStopRow("stopRow".getBytes())
        .setCaching(1000)
        .setBatch(10)
        .setMaxResultSize(-1);
ResultScanner scanner = table.getScanner(scan);
Iterator<Result> it = scanner.iterator();
while (it.hasNext()){
    Result next = it.next();
    // ...
}
```

### 数据查询

HBase 客户端在 scan 操作时根据 Region 中的 startKey 和 stopKey 将 scan 切分为多个小的 scan，每个小的 scan 对应一个 Region，然后将这些小的 scan 请求发送到对应的 RegionServer。

#### 构建 Scanner

RegionServer 接收到客户端 scan 请求后，首先构建三层 KeyValueScanner 体系，包括：RegionScanner, StoreScanner, MemStoreScanner 和 StoreFileScanner。其中 RegionScanner 位于最顶层，一个 RegionScanner 包含多个 StoreScanner，每个 StoreScanner 对应 Region 的一个 ColumFamily；StoreScanner 位于整个体系的第二层，一个 StoreScanner 由一个 MemStoreScanner 和多个 StoreFileScanner，每个 StoreFileScanner 对应一个 HFile。RegionScanner 以及 StoreScanner 并不负责实际查找操作，而是承担调度任务，实际负责数据查询任务的是 StoreFileScanner 和 MemStoreScanner。
```
                        +-------------------------+
                        |  RegionScanner(HRegion) |
                        +-------------------------+
                            |               |
        +---------------------+         +---------------------+
        |  StoreScnner(Store) |         |  StoreScnner(Store) |
        +---------------------+         +---------------------+
                                            |               |
                +----------------------------+          +-----------------------------+
                |  MemStoreScanner(MemStore) |          | StoreFileScanner(StoreFile) |
                +----------------------------+          +-----------------------------+
```
RegionServer 构建完 Scanner 体系之后还需要完成三个核心步骤：
- 过滤不必要的 Scanner
- seek 到每个 HFile/MemSotre 的 startKey，定位到 startKey 之后只需要调用 Scanner 的 next 方法就可以获取下一个数据

RegionServer 会为每个 HFile 创建一个 StoreFileScanner，但是查找的数据可以通过条件确定不属于某些 HFile，这些 HFile 对应的 StoreFileScanner 就不会参与到数据的查找过程中。过滤 Scanner 主要手段有三种：KeyRnage 过滤、TimeRange 过滤 和 布隆过滤器过滤。
- **KeyRange 过滤**：StoreFile 中所有 K-V 数据都是有序排列的，所以如果 scan 的 rowkey 范围 [startrow, stoprow] 与文件起始 key 范围 [firstkey, lastkey] 没有交集，就可以过滤掉该 StoreScanner
- **TimeRange 过滤**：StoreFile 元数据有一个关于该 HFile 的 TimeRange 属性 [miniTimestamp, maxTimestamp]，如果 scan 的 TimeRange 与该文件时间范围没有交集，就可以过滤掉该 StoreScanner
- **布隆过滤器过滤**：根据待检索的 rowkey 获取对应的 Bloom Block 并加载到内存(通常情况下，热点 Bloom Block 会常驻内存)，再用 hash 函数对待检索 rowkey 进行 hash，根据 hash 后的结果在布隆过滤器数据中进行寻址，即可确定待检索 rowkey 是否一定不存在于该 HFile

#### 读取数据

确定数据可能存在的 HFile 之后，RegionServer 需要在这些 HFile 中定位到数据所在的 Block 并且将 Block 从 HDFS 上读取到 RegionServer 的内存中，之后通过遍历的方式从 Block 中检索到待查找的数据返回。

RegionServer 在打开 HFile 时会将 HFile 的 Trailer 部分和 Load-on-open 部分加载到内存，通过在 Load-on-open 部分的索引树根节点 RootIndexBlock 上通过二分查找即可定位到 rowkey 所在的 DataBlock。
```java

```
定位到数据存储的 Block 之后，Scanner 会首先到 BlockCache 中查找是否有缓存过该 Block。BlockCache 保存了 BlockKey(HFileName_BlockIndex 构成，全局唯一) 和 Block 在 BlockCache 中的地址的映射。
```java

```
在 BlockCache 中不能查找到数据对应的 Block，就需要在 HDFS 上加载对应的 Block，HBase 通过 ```HFileBlock.FSReaderImpl.readBlockData``` 方法读取 HDFS 上的数据。HBase 会为每个 HFile 创建一个用于读取的数据流(FSDataInputStream)，对该 HFile 的所有读取操作都会使用这个 InputStream 进行操作。
```

```

使用 FSDataInputStream 读取 HFile 中的数据块，FSClient 首先会联系 NameNode 组件，NameNode 组件会做两件事：
- 找到属于这个 HFile 的所有 HDFSBlock 列表，确认待查找数据在哪个 HDFSBlock 上(HDFS 的 DataBlock 大小等于 128M)
- 确认定位到的 HDFSBlock 在哪些 DataNode 上，选择一个最优 DataNode 返回给客户端

NameNode 告知 HBase 可以去特定 DataNode 上访问特定 HDFSBlock，之后 HBase 请求对应的 DataNode 数据块，DataNode 找到指定的 HDFSBlock，seek 到指定偏移量，从磁盘读出指定大小的数据返回。DataNode 读取数据实际上是向磁盘发送读取指令，磁盘接收到读取指令之后会移动磁头到给定位置，读取出完整的 64K 数据返回

HDFS 的 Block 设计为 128M 是因为当数据量大到一定程度，如果 Block 太小会导致 Block 元数据非常庞大，使得 NameNode 成为整个集群瓶颈；HBase 的缓存策略是缓存整个 Block，如果 Block 太大容易导致缓存内存很容易耗尽

Block 数据读取到之后会缓存到 BlockCache，以便下次查询时提供性能。Block 中的数据是待检索的 K-V 数据，这部分数据的长度是不定的，因此只能通过遍历的方式查询：
```

```

#### 过滤数据

RegionServer 将构建的 StoreFileScanner 和  MemStoreScanner 合并成一个最小堆，按照 Scanner 排序规则将查找到的数据由小到大进行排序，保证 scan 出来的数据保证有序性。

scanner 查找出来的还需要再进一步的判断这些数据是否满足 TimeRange 条件、版本条件以及 Filter 条件：
- 检查 K-V 数据的 KeyType 是否是 Delete, DeleteColumn, DeleteFamily 等，如果是则直接忽略改列的所有版本
- 检查 K-V 数据的 Timestamp 是否再设定的 TimeRange 范围内，如果不再则忽略
- 检查 K-V 数据是否满足设定的 filter，如果不满足则忽略
- 检查 K-V 数据是否满足设定的版本数，忽略不满足的版本数据



