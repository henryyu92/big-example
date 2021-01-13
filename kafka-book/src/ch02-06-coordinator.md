# 协调器
当消费者配置了不同的分区策略，多个消费者之间的分区分配需要通过消费者协调器(ConsumerCoordinator) 和组协调器(GroupCoordinator) 来完成。

Kafka 旧版本客户端使用 ZooKeeper 监听完成消费者分区分配之间的协调工作。每个消费组在 ZK 上维护了 /consumer/<group> 路径，其下有三个子路径：
- ids：使用临时节点存储消费组中的消费者信息，临时节点路径名为 consumer.id_主机名-时间戳-UUID 
- owners：记录分区和消费者的对应关系
- offsets：记录消费组中消息的 offset

每个 broker、topic 和 partition 在 ZK 上