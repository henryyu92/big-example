## KafkaAdminClient

Kafka 提供的脚本工具对应着 `kafka.admin` 包下的 Command 类

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

# AdminManager


## 副本分配

Kafka 保证同一分区的不同副本分配在不同的节点上，不同分区的 leader 副本尽可能的均匀分布在集群的节点中以保证整个集群的负载均衡。

在创建主题的时候，如果通过 ```replica-assignment``` 指定了副本分配方案则按照指定的方案分配，否则使用默认的副本分配方案。使用 ```kafka-topics.sh``` 脚本创建主题时 ```AdminUtils``` 根据是否指定了机架信息(```broker.rack``` 参数，所有主题都需要指定)分为两种分配策略：
- ```assignReplicasToBrokersRackUnaware```：无机架分配方案
- ```assignReplicasToBrokersRackAware```：有机架分配方案

无机架分配方案直接遍历分区分配分区的每个副本，从一个指定的 broker 开始，副本在第一个副本指定间隔之后以轮询的方式分配在 broker 上。
```java
private def assignReplicasToBrokersRackUnaware(nPartitions: Int,
                                               replicationFactor: Int,
                                               brokerList: Seq[Int],
                                               fixedStartIndex: Int,
                                               startPartitionId: Int): Map[Int, Seq[Int]] = {
  // 存储分配方案
  val ret = mutable.Map[Int, Seq[Int]]()
  val brokerArray = brokerList.toArray
  // 分配的 broker 起始 Id
  val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
  // 分配的 partition 起始 Id
  var currentPartitionId = math.max(0, startPartitionId)
  // partition 中与第一个副本的间隔
  var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
  // 遍历分配分区
  for (_ <- 0 until nPartitions) {
    // 分区数大于 broker 数量，则每分配一轮就将间隔 +1
    if (currentPartitionId > 0 && (currentPartitionId % brokerArray.length == 0))
        nextReplicaShift += 1
    // 计算第一个副本分配的 brokerId
    val firstReplicaIndex = (currentPartitionId + startIndex) % brokerArray.length
    // 存储分区的分配方案
    val replicaBuffer = mutable.ArrayBuffer(brokerArray(firstReplicaIndex))
    // 遍历分区副本分配每个副本
    for (j <- 0 until replicationFactor - 1)
      // 计算副本分配的 brokerId
      replicaBuffer += brokerArray(replicaIndex(firstReplicaIndex, nextReplicaShift, j, brokerArray.length))
    ret.put(currentPartitionId, replicaBuffer)
    currentPartitionId += 1
  }
  ret
}

private def replicaIndex(firstReplicaIndex: Int, secondReplicaShift: Int, replicaIndex: Int, nBrokers: Int): Int = {
  // 计算与第一个副本的间隔
  val shift = 1 + (secondReplicaShift + replicaIndex) % (nBrokers - 1)
  (firstReplicaIndex + shift) % nBrokers
}
```
有机架的分配策略在分配的过程中加入了机架的影响，对同一个分区来说，当出现两种情况时当前 broker 上不分配副本：
- 当前 broker 所在的机架上已经存在分配了副本的 broker 并且存在还没有分配副本的机架
- 当前 broker 已经分配了副本并且存在还没有分配副本的 broker
```java
private def assignReplicasToBrokersRackAware(nPartitions: Int,
                                             replicationFactor: Int,
                                             brokerMetadatas: Seq[BrokerMetadata],
                                             fixedStartIndex: Int,
                                             startPartitionId: Int): Map[Int, Seq[Int]] = {
  // 获取 broker 和 机架的映射
  val brokerRackMap = brokerMetadatas.collect { case BrokerMetadata(id, Some(rack)) =>
    id -> rack
  }.toMap
  val numRacks = brokerRackMap.values.toSet.size
  // 按机架排序的 broker 列表
  val arrangedBrokerList = getRackAlternatedBrokerList(brokerRackMap)
  val numBrokers = arrangedBrokerList.size
  // 存储副本分配方案
  val ret = mutable.Map[Int, Seq[Int]]()
  val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
  var currentPartitionId = math.max(0, startPartitionId)
  var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
  // 遍历分区
  for (_ <- 0 until nPartitions) {
    if (currentPartitionId > 0 && (currentPartitionId % arrangedBrokerList.size == 0))
      nextReplicaShift += 1
    val firstReplicaIndex = (currentPartitionId + startIndex) % arrangedBrokerList.size
    val leader = arrangedBrokerList(firstReplicaIndex)
    val replicaBuffer = mutable.ArrayBuffer(leader)
    // 存储已经分配过副本的机架
    val racksWithReplicas = mutable.Set(brokerRackMap(leader))
    // 存储已经分配过副本的 broker
    val brokersWithReplicas = mutable.Set(leader)
    var k = 0
    // 遍历分配副本
    for (_ <- 0 until replicationFactor - 1) {
      var done = false
      while (!done) {
        // 副本分配的 broker
        val broker = arrangedBrokerList(replicaIndex(firstReplicaIndex, nextReplicaShift * numRacks, k, arrangedBrokerList.size))
        // broker 对应的机架
        val rack = brokerRackMap(broker)
          // Skip this broker if
          // 1. there is already a broker in the same rack that has assigned a replica AND there is one or more racks
          //    that do not have any replica, or
          // 2. the broker has already assigned a replica AND there is one or more brokers that do not have replica assigned
        if ((!racksWithReplicas.contains(rack) || racksWithReplicas.size == numRacks)
            && (!brokersWithReplicas.contains(broker) || brokersWithReplicas.size == numBrokers)) {
          replicaBuffer += broker
          racksWithReplicas += rack
          brokersWithReplicas += broker
          done = true
        }
        k += 1
      }
    }
    ret.put(currentPartitionId, replicaBuffer)
    currentPartitionId += 1
  }
  ret
}
```