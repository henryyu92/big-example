# 配置管理

```kafka-configs.sh``` 脚本是专门用来对配置进行操作的，也就是在运行状态下修改原有的配置达到动态变更的目的。脚本包含变更配置 alter 和查看配置 describe 这两种指令类型，支持主题、broker、用户和客户端的配置。

```kafka-configs.sh``` 使用 --entity-type 参数指定操作配置类型，使用 --entity-name 参数指定配置配置的名称，对应关系为：

|entity-type|entity-name|
|-|-|
|topics|主题名|
|brokers|brokerId|
|clients|clientId|
|users|用户名|
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--describe
--entity-type topics
--entity-name topic-config
```
alter 指令使用 --add-config 和 --delete-config 两个参数实现配置的增、改和删，多个配置参数之间用逗号(，)隔开：
```shell
bin/kafka-configs.sh --zookeeper localhost:2181 \
--alter \
--entity-type topics \
--entity-name topic-config \
--add-config clelanup.policy=compact,max.message.bytes=10000 \
--delete-config cleanup.policy 
```
使用 ```kafka-configs.sh``` 脚本来变更配置是在 ZooKeeper 中创建一个命名形式为 ```/config/<entity-type>/<entity-name>``` 的节点并将变更的配置写入这个节点，同时还会在 ZooKeeper 创建 ```/config/changes/config_change_<seqNo>``` 的持久顺序节点表示节点配置的变更。
```shell
get /config/topics/topic-config

ls /config/changes
```