## HBase 读流程

客户端从 HBase 中读取数据涉及到 RegionServer 的缓存、MemStore 以及 HFile，因此 HBase 的数据读取流程比较复杂。另外，由于更新操作是插入了新的以时间戳为版本数据，删除操作是插入了一条标记为 delete 标签的数据，读取数据时还需要根据版本以及删除标签进行过滤。

HBase 提供 get 和 scan 两种方式读取数据，其中 get 是 scan 的一种特殊形式。HBase 的整个数据读取流程可以分为两部分：Region 定位、数据查询。

### Region 定位

HBase 中表的数据是由多个 Region 构成，而这些 Region 是分布在整个集群的 RegionServer 上，因此客户端在读取数据时首先需要确定数据所在的 RegionServer，然后才能到对应的 RegionServer 上读取数据。

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

```hbase:meta``` 表中每一行数据代表一个 Region，其中 rowkey 由 TableName(表名)、StartRow(Region 起始 rowkey)、Timestamp(Region 创建时间戳)、EncodedName(字段 MD5)拼接而成，HBase 保证 ```hbase:meta``` 表始终只有一个 Region，这样可以保证 meta 表多次操作的原子性。


HBase 客户端缓存 ```hbase:meta``` 信息到 MetaCache，客户端在根据 rowkey 查询数据时首先会到 MetaCache 中查找 rowkey 对应的 Region 信息，如果查找不到或者根据对应的 Region 信息到对应的 RegionServer 上不能找到数据，则会到 ```hbase:meta``` 表中使用 Reserved Scan 获取新的 rowkey 对应的 Region 信息，然后将 ```(regionStartRow, region)``` 二元组信息存放在 MetaCache 中。

```hbase:meta``` 表也是在 RegionServer 上，HBase 中 ```hbase:meta``` 所在 RegionServer 的信息存储在 ZK 上，客户端在获取 rowkey 对应的 RegionServer 前需要从 ZK 上获取到 ```hbase:meta``` 表所在的 RegionServer。
```shell
get /

# 
```

为了避免 scan 的数据量过大导致网络带宽被占用和客户端 OOM 的风险，客户端将 scan 操作分解为多次 RPC 请求，每个 RPC 请求为一次 next 请求，next 请求返回的一批数据会缓存到客户端，缓存的数据行数可以由 scan 参数 ```caching``` 设定，默认值为 ```Integet.MAX_VALUE```。

为了防止列的数据量太大，HBase 的 scan 操作还可以通过参数 ```batch``` 设定每个 next 请求返回的列数；HBase 的 scan 操作还有 ```maxResultSize``` 参数用于设定每个 next 请求的数据大小，默认值 2G：
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

RegionServer 接收到客户端 scan 请求后，

