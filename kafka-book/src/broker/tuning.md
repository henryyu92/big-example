## 参数调优



### Broker 参数

- `broker.id`：`Broker` 的唯一标识，必须大于等于 0。broker 在启动时会在 ZooKeeper 中创建 `/brokers/ids/<brokerId>` 的临时节点，其他 broker 节点或客户端通过判断该结点是否存在来确定该 broker 的健康状态
- `broker.id.generation.enable`：是否开启自动生成 `brokerId` 功能，默认是 true。自动生成的 `brokerId` 必须超过参数 ```reserved.broker.max.id``` 配置的基准值，基准值默认是 1000，也就是说默认情况下自动生成的 brokerId 是从 1001 开始



- `auto.create.topic.enable`|true|是否开启自动创建主题的功能|
- `auto.leader.relablance.enable`|true|是否开启自动 leader 再均衡的功能|
- `background.threads`|10|执行后台任务的线程数|
- `compression.type`|producer|消息压缩类型|
- `delete.topic.enable`|true|是否可以删除主题|
- `leader.imbalance.check.interval.seconds`|300|检查 leader 是否分布不均衡的周期|
- `leader.imbalance.broker.percentage`|10|允许 leader 不均衡的比例，超过则会触发 leader 再均衡|
- `min.insync.replicas`|1|ISR 中最少的副本数|
- `num.io.threads`|8|处理请求的线程数|
- `num.network.threads`|3|处理接收和返回响应的线程数|
- `num.partitions`|1|创建主题时的默认分区数|
- `create.topic.policy.class.name`||创建主题时用来验证合法性的策略|
- `broker.rack`||配置 broker 的机架信息|