## 选主流程

Elasticsearch 中 Discovery 模块负责发现集群中的节点以及主节点的选举。ES 支持多种不同的 Discovery 类型，内置的为 Zen Discovery，封装了节点发现、选主等过程。

ZenDiscovery 选主流程如下：
- 每个节点计算最小的已知节点 ID