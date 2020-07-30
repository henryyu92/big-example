## Discovery

### 选主流程

Elasticsearch 中 Discovery 模块负责发现集群中的节点以及主节点的选举。ES 支持多种不同的 Discovery 类型，内置的为 Zen Discovery，封装了节点发现、选主等过程。

ES 的选主采用 Bully 算法，它假定所有的节点都具有唯一 ID，然后使用该 ID 对所有节点进行排序，任何时候的当前 leader 节点都是参与集群的最高节点 ID。

ZenDiscovery 选主流程如下：
- 每个节点计算最小的已知节点 ID，该节点为临时 Master 并向该节点发送 leader 投票
- 如果一个节点收到足够多的票数，并且该节点也为自己投票，那么它将扮演 leader 的角色，并开始发布集群状态

所有节点都会参与选举并投票，但是只有有资格成为 master 的节点(node.master 设置为 true) 的投票才有效。获得多少选票可以赢得选举胜利就是所谓的法定人数，在 ES 中可以有参数 `discovery.zen.minimum_master_nodes` 决定。为了避免脑裂，最小值应该不低于有 master 资格的节点数的一半。

选举过程的实现在 `ZenDiscovery#findMaster` 方法中，该方法查找当前集群中获取的 Master，然后从中选择新的 Master，如果选择成功则返回新的 Master，否则返回空。
```
// ping 所有节点，获取节点的响应列表，并将当前节点也加入到响应列表
List<ZenPing.PingResponse> fullPingResponses = pingAndWait(pingTimeout).toList();
fullPingResponses.add(new ZenPing.PingResponse(localNode, null, this.clusterState()));

// 筛选出有资格成为 master 的节点响应
final List<ZenPing.PingResponse> pingResponses = filterPingResponses(fullPingResponses, masterElectionIgnoreNonMasters, logger);

// 构建 activeMaster 列表记录节点响应中投票的 Master

// 构建 masterCandidates 列表记录所有的候选节点(node.master 为 true)

// 如果 activeMaster 为空则从 masterCandidate 中选举，选举细节在 ElectMasterService 中
```

在 ES 中，发送投票就是发送加入集群请求(JoinRequest)，得票就是申请加入该节点的请求的数量。收集投票进行统计在 `ZenDiscovery#handleJoinRequest` 中实现，接收到的连接被存储到 `ElectionContext#joinRequestAccumulator` 方法中，当节点检查收到的投票否足够时，就是检查加入它的连接数是否足够，其中会去掉没有 master 资格节点的投票。

选举出的临时 Master 有两种情况：该临时 Master 是本节点或非本节点。如果临时 Master 是本节点：
- 等待足够多的具备 Master 资格的节点加入本节点(投票达到法定人数)，完成选举
- 超时(默认 30s)后还没有满足数量的 join 请求则选举失败，需要进行下一轮选举
- 选举成功后发布新的 clusterState

如果临时 Master 不是本节点：
- 不再接受其他节点的 join 请求
- 向 Master 发送加入请求，并等待回复，等待时间默认 1m，超时后重试，默认重传 3 次，这个实现在方法 joinElectedMaster 中
- 最终当选的 Master 会先发布集群状态，才确认客户的 join 请求，因此 joinElectedMaster 返回代表接收到了 join 请求的确认，并且已经收到了集群状态

### 节点失效检测

节点失效检测会监控节点是否离线，然后处理其中的异常。ES 中有两种失效检测：
- NodeFaultDetection 由 Master 节点执行检测加入的集群的节点是否异常
- MasterFaultDetection 由集群中非 Master 节点检测 Master 节点是否异常

ES 中节点的失效检测都是定期(默认 1s) 发送 ping 请求探测节点是否正常，默认当失败达到 3 次或者连接模块通知节点离线则认为节点离开，需要处理节点离开事件。

NodeFaultDetection 事件处理在 `ZenDiscovery#handleNodeFailure` 中执行 `NodeRemovalClusterStateTaskExecutor#execute` 方法中，首先检查当前集群总节点数是否达到法定节点数(过半)，如果不足则会放弃 Master 身份，重新加入集群。

MasterFaultDetection 事件处理会重新加入集群，本质是该节点重新执行一遍选主流程，具体实现在 `ZenDiscovery#handleMasterGone` 方法中。

