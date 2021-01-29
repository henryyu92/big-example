# KafkaAdminClient

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