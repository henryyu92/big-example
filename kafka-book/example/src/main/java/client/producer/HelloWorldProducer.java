package client.producer;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class HelloWorldProducer {

    private static final String BROKER = System.getProperty("broker", "localhost:19092");
    private static final String TOPIC = System.getProperty("topic", "topic-hello");

    public static void main(String[] args) {

        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "hello world");
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                exception.printStackTrace();
            } else {
                System.out.println("record send result" + metadata);
            }
        });

        producer.close(Duration.ofSeconds(10));
    }

}
