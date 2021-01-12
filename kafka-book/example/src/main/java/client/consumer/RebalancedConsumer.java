package client.consumer;

import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class RebalancedConsumer {

    private static final String BROKER = System.getProperty("broker", "localhost:19092");

    private static final Map<TopicPartition, Long> partitionOffset = new HashMap<>();

    public static void main(String[] args) {

        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", BROKER);
        properties.setProperty("key.deserializer", StringDeserializer.class.getName());
        properties.setProperty("value.deserializer", StringDeserializer.class.getName());
        properties.setProperty("group.id", "rebalanced-group");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(Collections.singletonList("topic-rebalanced"), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                Map<TopicPartition, OffsetAndMetadata> partitionMetadata = new HashMap<>();
                for (TopicPartition partition : partitions){
                    // 再均衡发生之后同步提交消费位移，避免消费位移没有提交
                    long position = consumer.position(partition);
                    partitionMetadata.put(partition, new OffsetAndMetadata(position));
                    partitionOffset.put(partition, position);
                }
                consumer.commitSync(partitionMetadata, Duration.ofSeconds(10));
                partitionMetadata.clear();
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                for (TopicPartition partition : partitions){
                    consumer.seek(partition, getPartitionOffset(partition));
                }
            }
        });
    }

    private static long getPartitionOffset(TopicPartition partition){
        return partitionOffset.get(partition);
    }
}
