## HMaster

HMaster 是 Master Server 的实现，主要监控集群中的 RegionServer 实例。在集群中，Master 一般部署在 NameNode 上。Master 通过 Zookeeper 实现主从切换。

由于客户端直接根据 hbase:meta 表中的 RgionServer地址去直接和 RegionServer 对话，并且 hbase:meta 表并不在 Master 上，因此即使 Master 宕机，集群依然可以正常工作。但是由于 Master 负责RegionServer 的 failover 和 Region 的切分，所以当 Master 宕机时仍然需要尽量重启。

HMaster 在后台启动的线程：

- LoadBalancer: LoadBalancer 会定期检查 region 是否正常，如果不正常会移动 region 来达到负载均衡；可以通过 ```hbase.balancer.period``` 参数设置 loadbalancer 的周期，默认是 300000
- CatalogJanitor: CatalogJanitor 线程周期的去检查并清理 hbase:meta 表

HMaster 将管理操作及其运行状态(例如服务器崩溃、表创建和其他 DDL 操作)记录到自己的 WAL 文件中，WAL 存储在 MasterProcWALs 目录下。