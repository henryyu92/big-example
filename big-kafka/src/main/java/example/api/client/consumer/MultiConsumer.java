package example.api.client.consumer;

import example.api.client.ConfigurationBuilder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 消费者是有状态的，使用多线程的方式增大消费者吞吐量
 */
public class MultiConsumer {

    public static void main(String[] args) {
        multiConsumerThread("localhost:9092", "group-1", 1, "test");
        multiConsumerThread("localhost:9092", "group-2", 1, "test");
//        multiConsumerThread("localhost:9092", "group-3", 1, "test");
    }


    /**
     * 每个 Consumer 维护着一个 TCP 连接，Consumer 过多会占用过多连接
     *
     * @param brokers
     * @param groupId
     * @param topics
     */
    public static void multiConsumerThread(String brokers, String groupId, Integer threadNum, String... topics) {

        Properties properties = ConfigurationBuilder
                .newConsumerConfigBuilder(brokers, groupId)
                .keyDeserializer(StringDeserializer.class)
                .valueDeserializer(StringDeserializer.class)
                .build();

        for (int i = 0; i < threadNum; i++) {
            new Thread(new ConsumerThread(properties, topics)).start();
        }
    }

    /**
     * 使用一个 Consumer 处理 IO，多个 Thread 作为 Handler 处理业务，需要记录失败消息的 offset 用于再次消费
     *
     * @param brokers
     * @param groupId
     * @param topics
     */
    public static void multiHandlerThread(String brokers, String groupId, String... topics) {
        final int threadNum = 10;
        final ExecutorService pool = new ThreadPoolExecutor(
                threadNum,
                threadNum,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1024),
                new ThreadPoolExecutor.AbortPolicy());

        Properties properties = ConfigurationBuilder
                .newConsumerConfigBuilder(brokers, groupId)
                .build();

        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);
        consumer.subscribe(Arrays.asList(topics));
        try {

            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                if (!records.isEmpty()) {
                    pool.submit(new RecordHandler(records));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            consumer.close();
        }

    }

    static class RecordHandler implements Runnable {
        public final ConsumerRecords<String, String> records;

        public RecordHandler(ConsumerRecords<String, String> records) {
            this.records = records;
        }

        @Override
        public void run() {
            try{
                // process

            }catch (Exception e){
                // 记录失败消息的 offset
            }
        }
    }


    static class ConsumerThread implements Runnable {

        private KafkaConsumer<String, String> kafkaConsumer;

        public ConsumerThread(Properties props, String... topics) {
            this.kafkaConsumer = new KafkaConsumer<>(props);
            kafkaConsumer.subscribe(Arrays.asList(topics));
        }

        @Override
        public void run() {
            try {
                while (true) {
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<String, String> record : records) {
                        // process
                        System.out.println("consumer: " + kafkaConsumer + ", record: " + record
                                + ", offset: " + kafkaConsumer.committed(new TopicPartition(record.topic(), record.partition())));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                kafkaConsumer.close();
            }
        }
    }
}
