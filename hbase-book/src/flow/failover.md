
## 故障转移

HBase 系统中主要有两类服务进程：Master 进程和 RegionServer 进程。Master 主要负责集群管理调度，发生故障的概率比较低，RegionServer 负责用户的读写，压力会比较大，逻辑比较复杂，因此发生故障的概率较大。RegionServer 有一些常见的异常：
- Full GC 异常：长时间的 Full GC 是导致 RegionServer 宕机的最主要原因
- HDFS 异常：RegionServer 读写数据是直接操作 HDFS 的，HDFS 异常会导致 RegionServer 异常
- 机器宕机：物理节点宕机会导致 RegionServer 进程直接挂掉

HBase 采用了热备方式实现 Master 的高可用，集群中至少会启动两个 Master 进程，进程启动后会在 ZK 上的 Master 节点注册，注册成功后则会成为 Active Master，未注册成功则为 Backup Master，当 Active Master 异常，Backup Master 就会竞争注册成为 Active Master。

RegionServer 宕机之后 HBase 会在检测到宕机之后将该 RegionServer 上的所有 Region 重新分配到集群中其他正常的 RegionServer 上，再根据 HLog 进行数据丢失恢复，恢复完成后对外提供服务：
- Master 检测到 RegionServer 宕机：HBase 检测宕机是通过 ZK 实现的，正常情况下 RegionServer 会周期性的向 ZK 发送心跳，查过一定时间(SessionTimeout) 没有接收到心跳，ZK 就会认为 RegionServer 宕机离线，并将消息通知给 Master
- 切分未持久化数据的 HLog 日志：RegionServer 宕机之后已经写入 MemStore 但还没有持久化到文件的这部分数据会丢失，通过 HLog 可以恢复这部分数据。HLog 中所有Region 的数据都混合存储在同一个文件中，为了使这些数据能够按照 Region 进行组织回放，需要将 HLog 日志进行切分再合并，同一个 Region 的数据最终合并在一起，方便后续按照 Region 进行数据恢复
- Master 重新分配宕机 RegionServer 上的 Region：RegionServer 宕机后 Region 属于不可用状态，所有路由到这些 Region 的请求都会返回异常。Master 会将这些不可用的 Region 重新分配到其他 RegionServer 上，但此时这些 Region 还并没有上线，因为之前存储在 MemStore 中还没有落盘的数据需要回放
- 回放 HLog 日志补救数据：根据 HLog 按照 Region 切分的结果，在指定的 RegionServer 上回放，将还没有来得及落盘的数据补救回来
- 修复完成之后，对外提供服务，完成宕机恢复

#### RegionServer 宕机检测
HBase 使用 ZK 协助 Master 检测 RegionServer 宕机，所有 RegionServer 在启动之后都会在 ZK 节点 /rs 上注册一个子节点，这个子节点的类型为临时节点，一旦连接在该节点上的客户端因为某些原因发生会话超时，这个临时节点会自动消失，并通知 watch 在该临时节点上的其他客户端。

在一些情况下，由于 RegionServer 发生了长时间的 GC 导致心跳长时间断开，这是临时节点就会离线，导致 Master 认为 RegionServer 宕机。ZK 的会话超时时间可以在配置文件中设置，参数是 zookeeper.session.timeout，默认是 180s，但是此参数的调整需要配合 ZK 服务器的参数 tickTime, minSessionTimeout, maxSessionTimeout
