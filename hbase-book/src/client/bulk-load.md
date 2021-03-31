## BulkLoad

HBase 提供了 BulkLoad 将数据生成 HFile 然后直接加载到对应的 Region 下的 Column Family 内。bulkLoad 是一种完全离线的快速批量写入方案，在生成 HFile 时服务端不会有任何 RPC 调用，只有在 load HFile 时会调用 RPC，不会对集群产生巨大压力。

BulkLoad 首先使用 MapReduce 将待写入集群数据转换为 HFile 文件，再直接将这些 HFile 文件加载到在线集群中。

- HFile 生成阶段。这个阶段会运行一个 MapReduce 任务，mapper 方法将据组装成一个复合 KV，其中 key 是 rowkey，value 可以是 KeyValue 对象、Put 对象甚至 Delete 对象；reduce 方法由 HBase 负责，通过方法 HFileOutputFormat2.configureIncrementlLoad() 进行配置，这个方法主要负责以下事项：
  - 根据表信息配置一个全局有序的 partitioner
  - 将 partitioner 文件上传到 HDFS 集群并写入 DistributedCache
  - 设置 reduce task 的个数为目标表 Region 的个数
  - 设置输出 key-value 类满足 HFileOutputFormat 所规定的格式要求
  - 根据类型设置 reducer 执行相应的排序(KeyValueSortReducer 或者 PutSortReducer)
- HFile 导入阶段。HFile 准备就绪之后，就可以使用工具 complietebulkload 将 HFile 加载到在线 HBase 集群。complitebulkload 工具负责以下工作
  - 依次检查第一步生成的所有 HFile 文件，将每个文件映射到对应的 Region
  - 将 HFile 文件移动到对应 Region 所在的 HDFS 文件目录下
  - 告知 Region 对应的 RegionServer，加载 HFile 对外提供服务

如果在 BulkLoad 的中间过程中 Region 发生了分裂，completebulkload 工具会自动将对应的 HFile 文件按照新生成的 Region 边界切分成多个 HFile 文件，保证每个 HFile 都能与目标表当前 Region 相对应，但这个过程需要读取 HFile 内容，因而并不高效。需要尽量减少 HFile 生成阶段和 HFile 导入阶段的延迟，最好能够在 HFile 生成之后立刻执行 HFile 导入

通常有两种方法调用 completebulkload 工具：

```shell
bin/hbase org.apache.hadoop.hbase.tool.LoadIncrementalHFiles <hdfs://storefileoutput> <tablename>

bin/hadoop jar ${HBASE_HOME}/hbase-server-Version.jar completebulkload <hdfs://storefileoutput> <tablename>
```

如果表没在集群中，工具会自动创建表。



### Spark
