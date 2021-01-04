package client.producer;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class HelloWorldProducer {

    private static final String BROKER = System.getProperty("broker", "localhost:9092");

    public static void main(String[] args) {

        Map<String, Object> config = new HashMap<String,Object>() {
            {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            }
        };

        KafkaProducer<String, String> producer = new KafkaProducer<>(config);
        ProducerRecord<String, String> record = new ProducerRecord<>("topic-hello", "hello world");
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
