package example;

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

    public static ConsumerConfigBuilder newConsumerConfigBuilder(){
        return new ConsumerConfigBuilder();
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

        ConsumerConfigBuilder(){
            properties = new Properties();
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
