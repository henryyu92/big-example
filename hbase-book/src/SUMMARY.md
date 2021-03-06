# Summary

- [HBase 介绍](./introduce/introduce.md)
  - [数据模型](./introduce/data-model.md)
  - [架构](./introduce/archi.md)
- [客户端](./client/client.md)
  - [API](./ch01-01-put.md)
  - [Get](./ch01-02-get.md)
  - [Scan](./ch01-03-scan.md)
- [核心组件](./ch02-00-component.md)
  - [Region](./ch02-01-region.md)
  - [BlockCach](./ch02-02-block-cache.md)
  - [HLog](./ch02-03-hlog.md)
  - [HFile](./ch02-04-hfile.md)
  - [Coprocessor](./ch02-05-coprocessor.md)
- [核心流程](./ch03-00-flow.md)
  - [写数据](./ch03-01-write.md)
  - [读数据](./ch03-02-read.md)
  - [Region 合并](./ch03-03-region-compaction.md)
  - [负载均衡](./ch03-04-loadbalance.md)
  - [故障转移](./ch03-05-failover.md)
  - [复制](./ch03-06-replication.md)
  - [快照](./ch03-07-snapshot.md)