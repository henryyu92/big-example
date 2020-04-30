package example.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class Main {

    private static volatile boolean isRunning = true;


    public static void main(String[] args) {

    }

    /**
     * Kafka 消费者编程模型
     * @param properties
     * @param topic
     */
    private static <K, V> void model(Properties properties, String topic){
        // 初始化消费者实例
        KafkaConsumer<K, V> consumer = new KafkaConsumer<>(properties);
        // 订阅主题
        consumer.subscribe(Arrays.asList(topic));
        try{
            while (isRunning){
                // 拉取消息
                ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(1000));
                // 消费消息
                for (ConsumerRecord<K, V> record : records){
                    System.out.println(record);
                }
                if (records.isEmpty()){
                    isRunning = false;
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally{
            // 关闭消费者
            consumer.close();
        }
    }
}
