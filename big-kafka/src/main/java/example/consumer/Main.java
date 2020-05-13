package example.consumer;

import example.ConfigurationBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import scala.sys.Prop;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class Main {

    private static volatile boolean isRunning = true;


    public static void main(String[] args) {

    }

    /**
     * Kafka 消费者编程模型
     *
     * @param properties
     * @param topic
     */
    private static <K, V> void model(Properties properties, String topic) {
        // 初始化消费者实例
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties);
        // 订阅主题
        consumer.subscribe(Collections.singletonList(topic));
        try {
            while (isRunning) {
                // 拉取消息
                ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(1000));
                // 消费消息
                for (ConsumerRecord<K, V> record : records) {
                    System.out.println(record);
                }
                if (records.isEmpty()) {
                    isRunning = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 关闭消费者
            consumer.close();
        }
    }

    /**
     * 订阅指定分区
     *
     * @param properties
     * @param topics
     * @param <K>
     * @param <V>
     */
    private static <K, V> void subscribePartiton(Properties properties, String... topics) {
        // 初始化消费者实例
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties);
        // 订阅主题指定分区
        List<TopicPartition> partitions = Arrays.stream(topics)
                // 通过 partitionsFor 方法从 metadata 中获取到 topic 的分区信息
                .flatMap(topic -> consumer.partitionsFor(topic).stream())
                .map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition()))
                .collect(Collectors.toList());
        consumer.assign(partitions);
        try {
            while (isRunning) {
                // 拉取消息
                ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(1000));
                // 消费消息
                for (ConsumerRecord<K, V> record : records) {
                    System.out.println(record);
                }
                if (records.isEmpty()) {
                    isRunning = false;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // 关闭消费者
            consumer.close();
        }
    }

    /**
     * seek 到指定 offset 开始消费
     *
     * @param properties
     * @param topics
     * @param <K>
     * @param <V>
     */
    private static <K, V> void seekOffset(Properties properties, String... topics) {

        // 初始化消费者实例
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties);

    }

    /**
     * 手动提交消费位移
     *
     * @param properties
     * @param topics
     * @param <K>
     * @param <V>
     */
    private static <K, V> void manualOffsetSubmit(Properties properties, String... topics) {

        // 手动提交 offset 需要设置 enable.auto.commit 为 false
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, String.valueOf(false));

        // 初始化消费者实例
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties);

    }

    /**
     * 消费者模型
     *
     * @param broker
     * @param groupId
     */
    public static void modelConsumer(String broker, String groupId) {
        Properties properties = ConfigurationBuilder
                .newConsumerConfigBuilder(broker, groupId)
                .build();
    }



}
