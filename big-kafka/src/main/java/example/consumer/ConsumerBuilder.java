package example.consumer;

import example.ConfigurationBuilder;

public class ConsumerBuilder {

    private ConsumerBuilder(String broker, String groupId){
        configurationBuilder = configurationBuilder.newConsumerConfigBuilder(broker, groupId);
    }

    private ConfigurationBuilder configurationBuilder;


    public static ConsumerBuilder newConsumerBuilder(String broker, String groupId){
        ConsumerBuilder builder = new ConsumerBuilder(broker, groupId);
        return builder;
    }
}
