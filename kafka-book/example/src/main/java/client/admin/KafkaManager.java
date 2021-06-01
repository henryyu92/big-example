package client.admin;

import java.util.Collections;
import java.util.Properties;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.NewTopic;

public class KafkaManager {

    private final AdminClient admin;

    public KafkaManager(String brokers){
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", brokers);
        admin = KafkaAdminClient.create(properties);
    }

    public void createTopic(String topic, int partitions, short replicas){

        NewTopic newTopic = new NewTopic(topic, partitions, replicas);
        admin.createTopics(Collections.singleton(newTopic));
    }
}
