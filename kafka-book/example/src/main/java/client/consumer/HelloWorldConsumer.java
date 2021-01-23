package client.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HelloWorldConsumer {

    private static final String BROKER = System.getProperty("broker", "127.0.0.1:19092");

    public static void main(String[] args) throws InterruptedException {

        Map<String, Object> config = new HashMap<String, Object>(){
            {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
                put(ConsumerConfig.GROUP_ID_CONFIG, "hello-group");
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            }
        };

        KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(config);

        consumer.subscribe(Collections.singletonList("topic-demo-1"));
        // 三种订阅模式混合使用会抛出 IllegalStateException
        // consumer.subscribe(Pattern.compile("[a-zA-Z-]*hello"));

        while (true){
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            for (ConsumerRecord<String, String> record : records){

                System.out.println(record);
            }
        }

    }
}
