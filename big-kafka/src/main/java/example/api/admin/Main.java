package example.api.admin;

import example.ConfigurationBuilder;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class Main {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        consumerOffset(adminClient("localhost:9092"));
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

    public static void consumerOffset(AdminClient client) throws ExecutionException, InterruptedException {
        ListConsumerGroupsResult consumerGroups = client.listConsumerGroups();
        KafkaFuture<Collection<ConsumerGroupListing>> all = consumerGroups.all();

        Set<String> groupIds = all.get().stream().map(g -> g.groupId()).collect(Collectors.toSet());

        DescribeConsumerGroupsResult dc = client.describeConsumerGroups(groupIds);

        dc.all().get().forEach((x, y) -> {
            System.out.println("key = " + x + ", y = " + y);
        });
    }

}
