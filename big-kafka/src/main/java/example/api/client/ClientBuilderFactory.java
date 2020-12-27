package example.api.client;

import org.apache.kafka.clients.consumer.Consumer;

public abstract class ClientBuilderFactory {

    public static ConsumerBuilder newConsumerBuilder() {
        return new ConsumerBuilder();
    }

    public static class ConsumerBuilder<K, V> {

        private ConfigurationBuilder configBuilder;

        private Consumer<K, V> consumer;

        private ConsumerBuilder() {
        }

        public ConsumerBuilder(String brokers, String groupId){
            configBuilder = ConfigurationBuilder.newConsumerConfigBuilder(brokers, groupId);
        }


    }


}
