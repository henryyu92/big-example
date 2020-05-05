package example;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;

import java.util.Properties;

/**
 * 配置类
 */
public abstract class ConfigurationBuilder {

    public abstract Properties build();

    public abstract ConfigurationBuilder brokers(String brokers);

    /**
     * 设置 client ID
     * @param id
     * @return
     */
    public abstract ConfigurationBuilder id(String id);

    public static ProducerConfigBuilder newProducerConfigBuilder() {
        return new ProducerConfigBuilder();
    }

    public static ConsumerConfigBuilder newConsumerConfigBuilder(String brokers, String groupI){
        return new ConsumerConfigBuilder(brokers, groupI);
    }

    /**
     * Producer 配置
     */
    public static class ProducerConfigBuilder extends ConfigurationBuilder {

        Properties properties;

        private ProducerConfigBuilder() {
            this.properties = new Properties();
        }

        public ProducerConfigBuilder keySerializer(Class<?> serializerClass) {
            properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, serializerClass.getName());
            return this;
        }

        public ProducerConfigBuilder valueSerializer(Class<?> serializerClass) {
            properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializerClass.getName());
            return this;
        }

        public ProducerConfigBuilder interceptor(Class<? extends ProducerInterceptor> interceptor){
            properties.setProperty(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptor.getName());
            return this;
        }

        public ProducerConfigBuilder retries(int retries){
            properties.setProperty(ProducerConfig.RETRIES_CONFIG, String.valueOf(retries));
            return this;
        }

        @Override
        public ProducerConfigBuilder id(String id){
            properties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, id);
            return this;
        }

        @Override
        public ProducerConfigBuilder brokers(String brokers) {
            properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            return this;
        }

        @Override
        public Properties build() {
            return properties;
        }
    }

    /**
     * Consumer 配置
     */
    public static class ConsumerConfigBuilder extends ConfigurationBuilder {

        Properties properties;

        public enum AutoOffsetReset{
            /**
             * 从分区尾开始消费
             */
            Latest("latest"),
            /**
             * 从分区头开始消费
             */
            Earliest("earliest"),
            /**
             * 直接抛出异常
             */
            None("none");

            private String state;

            AutoOffsetReset(String state){
                this.state = state;
            }
        }

        ConsumerConfigBuilder(String brokers, String groupI){
            properties = new Properties();
            properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupI);
        }

        public ConsumerConfigBuilder autoOffsetReset(AutoOffsetReset autoOffsetReset){
            if (autoOffsetReset == null){
                properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AutoOffsetReset.Latest.state);
            }
            properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset.state);
            return this;
        }

        @Override
        public Properties build() {
            return null;
        }

        @Override
        public ConsumerConfigBuilder id(String id) {
            return null;
        }

        @Override
        public ConsumerConfigBuilder brokers(String brokers) {
            return null;
        }
    }

}
