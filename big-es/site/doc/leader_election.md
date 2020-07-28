## 选主流程

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


```