package example.producer;

import example.ConfigurationBuilder;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class Main {

    public static void main(String[] args) {

        modelProducer("localhost:9092");

        interceptedProducer("localhost:9092");
    }

    /**
     * Kafka 生产者编程模型
     *
     * @param properties 生产者配置
     * @param topic      主题
     */
    private static <K, V> void model(Properties properties, String topic, V value) {
        // 创建生产者
        KafkaProducer<K, V> producer = new KafkaProducer<>(properties);
        // 创建消息
        ProducerRecord<K, V> record = new ProducerRecord<>(topic, value);
        try {
            // 发送消息
            producer.send(record);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 关闭生产者
        producer.close();
    }

    private static <K, V> void callbackSend(Properties properties, String topic, V value){
        // 创建生产者
        KafkaProducer<K, V> producer = new KafkaProducer<>(properties);
        // 创建消息
        ProducerRecord<K, V> record = new ProducerRecord<>(topic, value);

        try{
            producer.send(record, (metadata, exception) -> {
                if (exception != null){
                    // 消息重试或者记录失败消息
                    exception.printStackTrace();
                }
                System.out.println(metadata.topic() + "-" + metadata.partition() + "-" + metadata.offset());
            });
        }catch (Exception e) {
            e.printStackTrace();
        }

        producer.close();
    }

    /**
     * 默认生产者
     *
     * @param broker broker 列表
     */
    public static void modelProducer(String broker) {

        Properties properties = ConfigurationBuilder
                .newProducerConfigBuilder()
                .keySerializer(StringSerializer.class)
                .valueSerializer(StringSerializer.class)
                .brokers(broker)
                .id("producer.client.demo")
                .build();


        model(properties, "model-topic", "hello kafka");

    }

    public static void retriedProducer(String broker){
        Properties properties = ConfigurationBuilder.newProducerConfigBuilder()
                .keySerializer(StringSerializer.class)
                .valueSerializer(StringSerializer.class)
                .brokers(broker)
                .id("producer.client.demo")
                .retries(10)
                .build();
        callbackSend(properties, "retries-topic", "hello callback");
    }

    /**
     * 自定义拦截器生产者
     *
     * @param broker
     */
    public static void interceptedProducer(String broker) {
        Properties properties = ConfigurationBuilder
                .newProducerConfigBuilder()
                .keySerializer(StringSerializer.class)
                .valueSerializer(StringSerializer.class)
                .brokers(broker)
                .interceptor(PrefixNameInterceptor.class)
                .id("producer.client.demo")
                .build();

        model(properties, "interceptor-topic", "hello kafka");
    }

    /**
     * 自定义序列化器生产者
     *
     * @param broker
     */
    public static void serializedProducer(String broker) {

    }
}
