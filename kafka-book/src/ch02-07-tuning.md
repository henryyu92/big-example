# 参数调优

- ```fetch.min.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最小数据量，默认为 1B。Kafka 在收到 KafkaConsumer 的拉取请求时如果数据量小于这个值时需要等待直到足够为止，因此如果设置过大则可能导致一定的延时
- ```fetch.max.bytes```：设置 KafkaConsumer 在一次拉取请求中能从 Kafka 中拉取的最大数据量，默认为 52428800B(50MB)。
- ```fetch.max.wait.ms```：设置 KafkaConsumer 阻塞等待的时间，如果 Kafka 的数据量小于拉取的最小数据量则阻塞等待直到超过这个时间，可适当调整以避免延时过大
- ```max.partition.fetch.bytes```：用于配置从每个分区一次返回给 Consumer 的最大数据量，默认为 1048576B(1MB)。而 ```fetch.max.bytes``` 是一次拉取分区数据量之和的最大值
- ```max.poll.records```：设置 Consumer 在一次拉取中的最大消息数，默认 500。如果消息比较小可以适当调大这个参数来提升消费速度
- ```connection.max.idle.ms```：设置连接闲置时长，默认 540000ms(9 分钟)。闲置时长大于该值得连接将会被关闭
- ```exclude.internal.topics```：用于指定 Kafka 内部主题(__consumer_offsets 和 __transaction_state)是否可以向消费者公开，默认为 true，true 表示只能使用 subscribe(Collection) 的方式订阅
- ```receive.buffer.bytes```：设置 Socket 接收消息缓冲区(SO_RECBUF)的大小，默认为 5653B(64KB)，如果设置为 -1 表示使用操作系统的默认值
- ```send.buffer.bytes```：设置 Socket 发送消息缓冲区(SO_SENDBUF)的大小，默认为 131072B(128KB)，如果设置为 -1 表示使用操作系统的默认值
- ```request.timeout.ms```：设置 Consumer 请求等待的响应的最长时间，默认为 30000ms
- ```metadata.max.age.ms```：配置元数据的过期时间，默认值为 300000ms，如果元数据在限定时间内没有更新则强制更新即使没有新的 broker 加入
- ```reconnect.backoff.ms```：配置尝试重新连接指定主机之前的等待时间，避免频繁的连接主机，默认 50ms
- ```retry.backoff.ms```：配置尝试重新发送失败的请求到指定的主题分区之前等待的时间，避免由于故障而频繁重复发送，默认 100ms
- ```isolation.level```：配置消费者的事务隔离级别，可以为 "read_uncommiteed"，"read_committed"
- bootstrap.servers  ""    key.deserializer    消息 key 对应的反序列化类  value.deserializer    消息 value 对应的反序列化类  group.id  ""  消费者所属消费组的位移标识  client.id  ""  消费者 clientId  heartbeat.interval.ms  3000  分组管理时消费者和协调器之间的心跳预计时间，通常不高于 session.timeout.ms 的 1/3  session.timeout.ms  10000  组管理协议中用来检测消费者是否失效的超时时间  max.poll.interval.ms  300000  拉取消息线程最长空闲时间，超过此时间则认为消费者离开，将进行再均衡操作  auto.offset.reset  latest  有效值为 "earliest", "latest", "none"  enable.auto.commit  true  是否开启自动消费位移提交  auto.commit.interval.ms  5000  自动提交消费位移时的时间间隔  partition.assignment.strategy  RangeAssignor  消费者分区分配策略  interceptor.class  ""  消费者客户端拦截器
