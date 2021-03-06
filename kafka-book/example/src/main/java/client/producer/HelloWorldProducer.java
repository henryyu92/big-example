package client.producer;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class HelloWorldProducer {

    private static final String BROKER = System.getProperty("broker", "localhost:19092");
    private static final String TOPIC = System.getProperty("topic", "topic-hello");

    public static void main(String[] args) {

        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
        properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.setProperty(ProducerConfig.METADATA_MAX_AGE_CONFIG, "500");

        KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
        List<ProducerRecord<String, String>> records = new ArrayList<>();
        records.add(new ProducerRecord<>(TOPIC, "hello world"));
        records.add(new ProducerRecord<>(TOPIC, "hello kafka"));
        records.add(new ProducerRecord<>(TOPIC, "hello producer"));
        records.forEach(record ->{
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    exception.printStackTrace();
                } else {
                    System.out.println();
                    System.out.println("record send result" + metadata);
                }
            });
            try {
                TimeUnit.MINUTES.sleep(6);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        producer.close(Duration.ofSeconds(10));
    }
}
