## API

HBase 提供了数据操作的 API，

```java
// 获取配置
Configuration conf = HBaseConfiguration.create();
// 建立链接，Connection 是线程安全的
Connnection conn = ConnectionFactory.createConnection(conf);
// Table 是线程不安全的
Table table = conn.getTable(TableName.valueOf("table-name");
// 构造请求
Get get = new Get("rowkey".getBytes());
Result result = table.get(get);
Cell cell = result.current();
System.out.println(new String(cell.getValueArray()));
```

- HBase 默认会加载 `hbase-default.xml` 和 `hbase-site.xml` 文件中的配置，然后根据配置初始化 `Connection`
- `Connection` 是线程安全的，维护了客户端到 HBase 集群的连接，每个进程只需要维护一个 `Connection` 实例即可。`Connection` 缓存了集群的 Meta 信息，请求都通过缓存的 Meta 信息直接定位到对应的 `RegionServer`
- `Table` 由 `Connection` 初始化，实现了访问数据的 API 操作，请求执行完之后需要关闭

### GET

`Get` 操作用于查询指定 `rowkey` 的数据，默认情况下请求返回数据的最新版本，通过设置版本参数可以返回多个版本的数据：

- `setTimeRange`：设置返回指定范围内的版本
- `readVersions`：获取指定版本的数据
- `readAllVersions`：获取所有版本的数据

```JAVA
Get get = new Get("rowkey".getBytes());
get.setAllVersions();
Result result = table.get(get);
```



### PUT

`Put` 操作将数据写入到 HBase 中，每次 Put 操作都会创建一个新版本的 Cell，默认情况下系统使用 ```currentTimeMillis```，可以在 Put 的时候指定版本，但是系统使用时间戳作为版本为了计算 TTL，因此最好不要自行设置版本。

```java
Put put = new Put("rowkey".getBytes());
put.addColumn(Bytes.toBytes("family", Bytes.toBytes("qualifier", Bytes.toBytes("value"))));
table.put(put);
```

HBase 客户端支持批量 Put 操作，但是由于 put 会写入到不同的 `RegionServer`，因此不能保证批量 Put 操作的原子性。

### DELETE

HBase 中的 `Delete` 操作不会立即删除数据，而是通过写入 “墓碑” 消息使 `RegionServer` 在执行 `Major Compaction` 的时候将数据清除。

```JAVA
Delete delete = new Delete("rowkey".getBytes());
table.delete(delete);
```



### SCAN

`Scan` 操作可以根据指定的维度批量获取数据，

- `withStartRow`：设置扫描的开始 rowkey，如果 rowkey 不存在则从下一个位置开始扫描
- `withStopRow`：设置扫描的结束 rowkey

```java
Scan scan = new Scan();
scan.withStartRow("start-row".getBytes());
scan.withStopRow("stop-row".getBytes());
ResultScanner rs = table.getScanner(scan);
for(Result r = rs.next; r != null; r = rs.next()){
    // ...
}finally{
    rs.close();
}
```

`ResultScanner.next()` 方法先从缓存队列中获取数据，如果队列中数据不足则会发起 `loadCache` 操作，也就是客户端向服务器发送 RPC 请求以获取数据，客户端在接收到服务器端发送的数据后对返回的 cell 进行重组并将重组后的结果放入到缓存中。

![scan]()

`RegionServer` 为了避免 RPC 请求耗尽资源，会对多个维度进行限制，一旦某个维度资源达到阈值，就马上把当前拿到的 cell 返回给客户端，这样客户端拿到的 result 可能不是一行完整的数据，因此需要和之前获取到的 cell 进行重组。Scan 过程中涉及的资源限制：

- ```caching```：设置每次 rpc 请求获取的最大行数
- ```batch```：设置调用 next 方法返回的最大列数
- ```allowPartial```：设置是否容忍部分数据，如果为 true 则不会重组而直接将返回 result 数据返回
- ```maxResultSize```：设置每次 rpc 请求获取的最大字节数，默认 2 M

```java
Scan scan = new Scan();
scan.setBatch(2).setCaching(3).setMaxResultSize()
```



### Filter

HBase 在 Scan 的时候可以设置多个 Filter，使得大量无效数据可以在服务端内部过滤，相比直接返回全表数据到客户端然后在客户端过滤要高效的多。

```java
Scan scan = new Scan();
// 返回列为指定前缀的数据
scan.setFilter(new ColumnPrefixFilter("prefix".getBytes()));
```

- `PrefixFilter`：返回 `rowkey` 为指定前缀的数据，可以转换成通过设置 `startRow` 和 `stopRow` 的方式更加高效率的扫描
- `PageFilter`：限制单个 Region 返回的行数，当 Region 中扫描的行数达到设置值则会立即返回，`PageFilter` 不能保证全局扫描的数量

- `SingleColumnValueFilter`：返回指定列为指定值的数据，没有指定的列的数据不会过滤掉，需要设置 `setFilterIfMissing`

HBase 提供的 `setFilter` 方法会覆盖之前设置的过滤器，当需要组合多个过滤条件时可以使用 `FilterList` 来组合：

```java
// 多个 Filter 必须同时满足
FilterList filterList = new FilterList(filter1, filter2);
```

### Admin

Admin 操作提供了对 HBase 的管理，包括命名空间和表的管理，Compaction 的执行，Region 的迁移等。

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

