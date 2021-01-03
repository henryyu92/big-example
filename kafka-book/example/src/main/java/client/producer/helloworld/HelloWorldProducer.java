package client.producer.helloworld;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

public class HelloWorldProducer {

    public static final String BROKER = "localhost:9092";
    public static final String TOPIC = "hello";

    public static void main(String[] args) {

        Map<String, Object> config = new HashMap<>() {
            {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            }
        };

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {

            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "hello world");
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    exception.printStackTrace();
                } else {
                    System.out.println("record send result" + metadata);
                }
            });
        }

    }

}
