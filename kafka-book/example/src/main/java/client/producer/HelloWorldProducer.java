package client.producer;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import javax.rmi.PortableRemoteObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        properties.setProperty(ProducerConfig.METADATA_MAX_AGE_CONFIG, "200");

        KafkaProducer<String, String> producer = new KafkaProducer<>(properties);
        List<ProducerRecord<String, String>> records = new ArrayList<>();
        records.add(new ProducerRecord<>(TOPIC, "hello world"));
        records.add(new ProducerRecord<>(TOPIC, "hello kafka"));
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
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });

        producer.close(Duration.ofSeconds(10));
    }


    // 1. Cluster(id = iiiMuA98Rveln-s5KOoaow, nodes = [192.168.10.107:19091 (id: 1 rack: null), 192.168.10.107:19092 (id: 2 rack: null), 192.168.10.107:19093 (id: 3 rack: null)], partitions = [Partition(topic = topic-hello, partition = 0, leader = 2, replicas = [2], isr = [2], offlineReplicas = [])], controller = 192.168.10.107:19091 (id: 1 rack: null))

}
