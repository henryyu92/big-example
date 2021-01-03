# 元数据更新

元数据是指 kafka 集群的元数据，这些元数据记录了集群中的主题、主题对应的分区、分区的 leader 和 follower 分配的节点等信息，当客户端没有需要使用的元数据信息时或者超过 ```metadata.max.age.ms```(默认 300000s 即 5 分钟) 没有更新元数据信息时会触发元数据的更新操作。

生产者启动时由于 bootstrap.server 没有配置所有的 broker 节点，因此需要触发元数据更新操作，当分区数量发生变化或者分区 leader 副本发生变化时也会触发元数据更新操作。

客户端的元数据更新是在内部完成的，对外不可见。客户端需要更新元数据时，首先根据 InFlightRequests 获取负载最低的节点 leastLoadedNode(未确认请求最少的节点)，然后向这个节点发送 MetadataRequest 请求来获得元数据信息，更新操作是由 Sender 线程发起，在创建完 MetadataRequest 之后同样会存入 InFlightRequests。
