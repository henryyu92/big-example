# 参数调优
消费者客户端在初始化的时候可以配置多个参数，合理的配置这些参数可以提供消费者客户端的性能。

## 消息拉取
|参数名|说明|默认值|
|:-----:|:-----|:-----:|
|`fetch.min.bytes`|单次拉取的最小数据量，不足则等待|1|
|`fetch.max.wait.ms`|数据不足时等待的最长时间||
|`fetch.max.bytes`|单次拉取的最大数据量(所有分区之和)|52428800B(50M)|
|`max.partition.fetch.bytes`|拉取单个分区的最大数据量|1048576B(1M)|
|`max.poll.records`|单次拉取的最大消息数(所有分区之和)|500|
|`request.timeout.ms`|消息拉取等待时间|30000(30s)|
|`metadata.max.age.ms`|元数据过期时间|300000(5m)|
|`isolation.level`|消费者的事务隔离级别，可以为 `read_uncommiteed` 和 `read_committed`||
|`heartbeat.interval.ms`|分组管理时消费者和协调器之间的心跳预计时间|3000|
|`session.timeout.ms`|组管理协议中用来检测消费者是否失效的超时时间|10000|
|`max.poll.interval.ms`|拉取消息线程最长空闲时间，超过此时间则认为消费者离开，将进行再均衡操作|300000|

## 位移提交
|参数名|说明|默认值|
|:-----:|:-----|:-----:|
|`enable.auto.commit`| 是否开启自动提交 offset | true|
|`auto.commit.interval.ms`|自动提交 offset 的时间间隔|5000|
|`auto.offset.reset`|无法获取 offset 时的消费起始位置，可以设置三种方式：<li>`earliest` 表示从</li><li>`latest`</li><li>`none`</li>| latest|
|`exclude.internal.topics`|指定 Kafka 内部主题是否可以向消费者公开|true|


## 网络
|参数名|说明|默认值|
|:-----:|:-----|:-----:|
|`connection.max.idle.ms`|连接闲置最大时长，超过将关闭连接|540000(9m)|
|`receive.buffer.bytes`|Socket 接收消息缓冲区(SO_RCVBUF)的大小，设置为 -1 表示使用操作系统的默认值|5653B(64KB)|
|`send.buffer.bytes`|Socket 发送消息缓冲区(SO_SENDBUF)的大小，设置为 -1 表示使用操作系统的默认值|131072B(128KB)|
|`reconnect.backoff.ms`|配置尝试重新连接指定主机之前的等待时间，避免频繁的连接主机|50|
|`retry.backoff.ms`|配置尝试重新发送失败的请求到指定的主题分区之前等待的时间，避免由于故障而频繁重复发送|100|

