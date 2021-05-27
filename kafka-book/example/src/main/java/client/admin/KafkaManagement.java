package client.admin;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.Collections;
import java.util.Map;

public class KafkaManagement {

    private final AdminClient admin;

    public KafkaManagement(String broker){
        admin = KafkaAdminClient.create((Map<String, Object>) null);
    }

    public void createTopic(String topic, int partitions, short replicas){

        NewTopic newTopic = new NewTopic(topic, partitions, replicas);
        admin.createTopics(Collections.singleton(newTopic));
    }
}
