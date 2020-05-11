package example.admin;

import example.ConfigurationBuilder;
import org.apache.kafka.clients.admin.*;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Main {

    public static void main(String[] args) {

    }


    public static AdminClient adminClient(String brokers){
        Properties properties = ConfigurationBuilder.newAdminConfigBuilder(brokers).build();

        AdminClient admin = KafkaAdminClient.create(properties);

        return admin;
    }

    public static void createTopic(AdminClient client, String topic, Integer partition, Integer replicaFactor){

    }

    public static void describeTopic(AdminClient client, String... topics){
        DescribeTopicsResult result = client.describeTopics(Arrays.asList(topics));

        result.all();
    }

}
