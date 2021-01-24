# 集群管理


## 配置管理
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

## 消费组管理
在 Kafka 中可以通过 ```kafka-consumer-groups.sh``` 脚本查看或变更消费组信息，通过 list 指令列出当前集群中所有的消费组：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```
通过 describe 指令可以查看指定消费组的详细信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \ 
--describe --group groupIdMonitor
```
其中 TOPIC 表示消费组订阅的主题，PARTITION 表示主题对应的分区号，CURRENT-OFFSET 表示消费组最新提交的消费位移，LOG-END-OFFSET 表示的是 HW，LAG 表示消息滞后的数量，CUNSUMER_ID 表示消费组的成员 ID，HOST 表示消费者 host，CLIENT_ID 表示消费者 clientId

消费组一共有 Dead、Empty、PreparingRebalance、Stable 这几种状态，正常情况下一个具有消费者成员的消费组的状态为 Stable，可以使用 state 参数查看消费组状态：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --state
```
如果消费组内没有消费者则消费组为 Empty 状态，可以通过 members 参数列出消费组内的消费者成员信息：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members
```
使用 verbose 参数可以查看每个消费者成员的分配情况：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--describe --group groupIdMonitor --members --verbose
```
使用 delete 指令删除指定的消费组，如果消费组中有消费者正在运行则会删除失败：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--delete --group groupIdMonitor
```

#### 消费位移管理
```kafka-consumer-groups.sh``` 脚本提供了通过 reset-offsets 指令来重置消费组内的消费位移，前提是该消费组内没有消费者运行：
```shell
bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
--group groupIdMonitor --all-topics --reset-offsets --to-earliest
```

## KafkaAdminClient
KafkaAdminClient 提供了 API 的方式对 Kafka 的主题、brokers、配置和 ACL 的管理：
```java
public class TopicManager {

    private static final String broker = "localhost:9092";
    private static final String topic = "topic-admin";

    public static Properties initConfig(){
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        return props;
    }

    public static void main(String[] args) {
        Properties props = initConfig();
        AdminClient admin = AdminClient.create(props);
        NewTopic newTopic = new NewTopic(topic, 4, (short) 1);
		// 创建 Topic
        CreateTopicsResult topicsResult = admin.createTopics(Collections.singleton(newTopic));
		// 查看 topic 配置信息
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        DescribeConfigsResult configsResult = admin.describeConfigs(Collections.singleton(resource));
		
		// 修改 topic 配置信息
        Map<ConfigResource, Config> configs = new HashMap<>();
        ConfigEntry configEntry = new ConfigEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
        configs.put(resource, new Config(Collections.singleton(configEntry)));
        AlterConfigsResult alterConfigsResult = admin.alterConfigs(configs);
        try{
			// 获取异步结果
            topicsResult.all().get();
            Config config = configsResult.all().get().get(resource);
            System.out.println(config);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            admin.close();
        }
    }
}
```