package example;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.StickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;

import java.util.Properties;

/**
 * 配置类
 */
public abstract class ConfigurationBuilder {

    public abstract Properties build();

    /**
     * 设置 broker 地址
     * @param brokers
     * @return
     */
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

        /**
         * 设置 offset reset 方式
         * @param autoOffsetReset
         * @return
         */
        public ConsumerConfigBuilder autoOffsetReset(AutoOffsetReset autoOffsetReset){
            if (autoOffsetReset == null){
                properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AutoOffsetReset.Latest.state);
                return this;
            }
            properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset.state);
            return this;
        }

        /**
         * 设置消费者分区器
         * @param assigner
         * @return
         */
        public ConsumerConfigBuilder assigner(Class<? extends ConsumerPartitionAssignor> assigner){
            if (assigner == null){
                properties.setProperty(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, StickyAssignor.class.getName());
                return this;
            }
            properties.setProperty(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, assigner.getName());
            return this;
        }

        /**
         * 设置消费者拦截器
         * @param interceptor
         * @param <K>
         * @param <V>
         * @return
         */
        public <K, V> ConsumerConfigBuilder interceptor(Class<? extends ConsumerInterceptor<K, V>> interceptor){
            properties.setProperty(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptor.getName());
            return this;
        }

        @Override
        public Properties build() {
            return properties;
        }

        @Override
        public ConsumerConfigBuilder id(String id) {
            properties.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, id);
            return this;
        }

        @Override
        public ConsumerConfigBuilder brokers(String brokers) {
            properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            return this;
        }
    }

}
