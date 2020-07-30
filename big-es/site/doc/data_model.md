## 数据模型

ES 的数据模型基于主从模式，多个副本中存在一个主副本和多个从副本，所有的数据写入操作都进入主副本，当主副本出现故障无法提供服务时，会从其他副本中选择合适的副本作为新的主副本。

数据写入流程：
- 写请求进入主副本节点，节点为该操作分配 SN(Serial Number)，使用该 SN 创建 UpdateRequest 结构，然后将该 UpdateRequest 插入自己的 prepare list
- 主副本节点将携带 SN 的 UpdateRequest 发往从副本节点，从节点收到后同样插入 prepare list，完成后给主副本节点回复一个 ACK