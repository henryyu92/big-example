## 负载均衡
HBase 中的负载均衡是基于数据分片设计，即 Region。HBase 的负载均衡主要涉及 Region 的迁移、合并、分裂等。

### Region 合并
Regin 合并用于空闲 Region 很多从而导致集群管理运维成本增加的场景，通过使用在线合并功能将这些 Region 与相邻的 Region 合并，减少集群中空闲的 Region 个数。

Region 合并的主要流程如下：
- 客户端发送 merge 请求给 Master
- Master 将待合并的所有 Region 都 move 到同一个 RegionServer 上
- Master 发送 merge 请求给该 RegionServer
- RegionServer 启动一个本地事务执行 merge 操作
- merge 操作将待合并的两个 Region 下线，并将两个 Region 的文件进行合并
- 将这两个 Region 从 hbase:meta 中删除，并将新生成的 Region 添加到 hbase:meta 中
- 将新生成的 Region 上线

HBase 使用 merge_region 命令执行 Region 合并，merge_region 操作是异步的，需要在执行一段时间之后手动检测合并是否成功，默认情况下 merge_region 只能合并相邻的两个 Region，如果可选参数设置为 true 则可以强制合并非相邻的 Region，风险较大不建议生产使用：
```shell
merge_region 'regionA', 'regionB', true
```
### Region 分裂
Region 分裂是 HBase 实现可扩展性的基础，在 HBase 中 Region 分裂有多种触发策略可以配置，一旦触发，HBase 会寻找分裂点，然后执行真正的分裂操作。

#### Region 分裂流程
负载均衡一个重要的应用场景就是系统扩容，通过负载均衡策略使得所有节点上的负载保持均衡，从而避免某些节点由于负载过重而拖慢甚至拖垮整个集群。在选择负载均衡策略之前需要明确系统的负载是什么，可以通过哪些元素来刻画，并指定相应的负载迁移计划。HBase 目前支持两种负载均衡策略：
- SimpleLoadBalancer：保证每个 RegionServer 的 Region 个数基本相等。因此在 SimpleLoadBalancer 策略中负载就是 Region 的个数，集群负载迁移计划就是从个数较多的 RegionServer 上迁移到个数较少的 RegionServer 上。这种负载均衡策略并没有考虑到 RegionServer 上的读写 QPS 以及 Region 中数据量的问题，可能会导致热点数据落在统一个 RegionServer 上从而导致节点负载较重
- StochasticLoadBalancer：对于负载的定义不再是 Region 个数这个简单，而是由多种独立负载加权计算的复合值，包括 Region 个数(RegionCountSkewCostFunction)、Region 负载、读请求数(ReadRequestCostFunction)、写请求数(WriteRequestCostFunction)、StoreFile 大小(StoreFileCostFunction)、MemStore 大小(MemStoreSizeCostFunction)、数据本地率(LocalityCostFunction)、移动代价(MoveCostFunction) 等，系统使用这个代价值来评估当前 Region 是否均衡，越均衡代价值越低

通过配置文件可以设置具体的负载均衡策略：
```xml
<property>
  <name>hbase.master.loadbalancer.class</name>
  <value>org.apache.hadoop.hbase.master.balancer.SimpleLoadBalancer</value>
</property>
```