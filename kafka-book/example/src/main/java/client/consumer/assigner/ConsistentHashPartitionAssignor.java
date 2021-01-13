package client.consumer.assigner;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.internals.AbstractPartitionAssignor;
import org.apache.kafka.common.TopicPartition;

/**
 * 基于一致性哈希算法实现分区分配
 */
public class ConsistentHashPartitionAssignor extends AbstractPartitionAssignor {

  @Override
  public Map<String, List<TopicPartition>> assign(Map<String, Integer> partitionsPerTopic,
      Map<String, Subscription> subscriptions) {

    return null;
  }

  @Override
  public String name() {
    return null;
  }
}
