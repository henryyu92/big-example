# Get



默认在 Get 操作没有显式指定版本的时候的到的是最新版本的数据，可以在 Get 的时候设置版本相关参数：
- Get.setMaxVersion() - 设定返回多个版本的数据
- Get.setTimeRange() - 设置返回指定版本的数据

### Delete

HBase 的 Delete 操作不会立马修改数据，因此是通过创建名为“墓碑”的标记在主合并的时候连同数据一起被清除。


## Admin

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