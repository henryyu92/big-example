package example.consumer.assigner;


import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;

import java.util.*;

/**
 * 随机分区器
 */
public class RandomAssigner extends AbstractPartitionAssignor {
    @Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic, Map<String, Subscription> subscriptions) {
        Map<String, List<String>> consumersPerTopic = consumersPerTopic(subscriptions);
        Map<String, List<TopicPartition>> assignment = new HashMap<>();
        for(String memberId : subscriptions.keySet()){
            assignment.put(memberId, new ArrayList<>());
        }

        for(Map.Entry<String, List<String>> topicEntry : consumersPerTopic.entrySet()){
            String topic = topicEntry.getKey();
            List<String> consumersForTopic = topicEntry.getValue();
            int consumerSize = consumersForTopic.size();

            Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
            if(numPartitionsForTopic == null){
                continue;
            }

            List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
            for(TopicPartition partition : partitions){
                int rand = new Random().nextInt(consumerSize);
                String randomConsumer = consumersForTopic.get(rand);
                assignment.get(randomConsumer).add(partition);
            }
        }
        return assignment;
    }

    @Override
    public String name() {
        return "random";
    }

    /**
     * 获取消费者订阅信息
     * @param consumerMetadata
     * @return
     */
    private Map<String, List<String>> consumersPerTopic(Map<String, Subscription> consumerMetadata){
        Map<String, List<String>> res = new HashMap<>();
        for(Map.Entry<String, Subscription> subscriptionEntry : consumerMetadata.entrySet()){
            // 消费者 Id
            String consumerId = subscriptionEntry.getKey();
            List<String> topics = new ArrayList<>();
            for(String topic : subscriptionEntry.getValue().topics()){
                topics.add(topic);
            }
            res.put(consumerId, topics);
        }
        return res;
    }
}
