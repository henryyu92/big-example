package example.api.consumer.assigner;


import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一消费组内实现广播消费，即消费组内的消费者可以消费同一个分区中的消息，但是不能消费同一个消息；消费组内广播需要考虑 offset 提交的问题
 */
public class BroadcastPartitionAssigner extends AbstractPartitionAssignor {
    @Override
    public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic, Map<String, Subscription> subscriptions) {
        Map<String, List<String>> consumerPerTopic = consumerPerTopic(subscriptions);
        // 消费者分区分配
        Map<String, List<TopicPartition>> assignment = new HashMap<>();

        subscriptions.keySet().forEach(memberId ->assignment.put(memberId, new ArrayList<>()));

        consumerPerTopic.entrySet().forEach(topicEntry->{
            String topic = topicEntry.getKey();
            List<String> members = topicEntry.getValue();
            Integer numPartitionsForTopic = partitionsPerTopic.get(topic);
            if (numPartitionsForTopic == null || members.isEmpty()){
                return;
            }
            List<TopicPartition> partitions = AbstractPartitionAssignor.partitions(topic, numPartitionsForTopic);
            if (!partitions.isEmpty()){
                members.forEach(memberId -> assignment.get(memberId).addAll(partitions));
            }
        });
        return assignment;
    }

    @Override
    public String name() {
        return "broadcast";
    }

    /**
     * 获取消费者订阅信息
     * @param consumerMetadata
     * @return
     */
    private Map<String, List<String>> consumerPerTopic(Map<String, Subscription> consumerMetadata){
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
