package client.consumer;

import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

public class KafkaConsumerTest {

    private static String broker = "localhost:9092";
    private static String topic = "topic-demo";
    private static String group = "group-demo";
    private static volatile boolean isRunning = true;

    // 配置消费者参数
    public static Properties initConfig(){
        Properties props = new Properties();
        // bootstrap.servers 指定 broker 列表
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
        // group.id 指定消费组名称
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        // key.deserializer 指定 key 的反序列化器
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // value.deserializer 指定 value 的反序列化器
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // client.id 指定客户端 id
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "client-id-demo");
        return props;
    }

    public static void main(String[] args) {

        Properties props = initConfig();
        // 初始化消费者实例
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        // 订阅主题
        consumer.subscribe(Collections.singletonList(topic));
        try{
            while (isRunning){
                // 拉取消息
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                // 消费消息
                for (ConsumerRecord<String, String> record : records){
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
