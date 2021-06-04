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
- `log.flush.interval.message`|Long.MAX_VALUE|日志文件中消息存入磁盘的阈值|
- `log.flush.interval.ms`||刷新日志文件的时间间隔，如果不配置则依据 log.flush.scheduler.interval.ms 值|
- `log.flush.scheduler.interval.ms`|Long.MAX_VALUE|检查日志文件是否需要刷新的时间间隔|
- `log.retention.bytes`|-1|日志文件的最大保留大小|
- `log.retention.hours`|168(7天)|日志存留时间，优先级最低|
- `log.retention.minutes`||和 log.retention.hours 一样，优先级居中|
- `log.retention.ms`||和 log.retention.hours 一样，优先级最高|
- `log.roll.hours`|168(7天)|经过多长时间后强制创建日志分段|
- `log.roll.ms`||和 log.roll.hours 一样，优先级较高|
- `log.segment.bytes`|1073741824(1G)|日志分段文件的最大值，超过这个值会强制创建一个新的日志分段|
- `log.segment.delete.delay.ms`|60000|从操作系统删除文件前的等待时间|
- `min.insync.replicas`|1|ISR 中最少的副本数|
- `num.io.threads`|8|处理请求的线程数|
- `num.network.threads`|3|处理接收和返回响应的线程数|
- `log.cleaner.enable`|true|是否开启日志清理功能|
- `log.cleaner.min.cleanable.ratio`|0.5|限定可执行清理操作的最小污浊率|
- `log.cleaner.threads`|1|用于日志清理的后台线程数|
- `log.cleanup.policy`|delete|日志清理策略|
- `log.index.interval.bytes`|4096|每隔多少个字节的消息量写入就添加一条索引|
- `log.index.size.max.bytes`|10485760(10M)|索引文件的最大值|
- `log.message.format.version`|2.0-IVI|消息格式中的版本|
- `log.message.timestamp.type`|CreateTime|消息中的时间戳类型|
- `log.retention.check.interval.ms`|300000|日志清理的检查周期|
- `num.partitions`|1|创建主题时的默认分区数|
- `create.topic.policy.class.name`||创建主题时用来验证合法性的策略|
- `broker.rack`||配置 broker 的机架信息|