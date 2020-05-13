package example;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.StickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

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

    public abstract ConfigurationBuilder interceptor(Class clazz);

    public static ProducerConfigBuilder newProducerConfigBuilder() {
        return new ProducerConfigBuilder();
    }

    public static ConsumerConfigBuilder newConsumerConfigBuilder(String brokers, String groupId){
        return new ConsumerConfigBuilder(brokers, groupId);
    }

    public static AdminConfigBuilder newAdminConfigBuilder(String brokers){
        return new AdminConfigBuilder(brokers);
    }

    /**
     * Producer 配置
     */
    public static class ProducerConfigBuilder extends ConfigurationBuilder {

        Properties properties;

        private ProducerConfigBuilder() {
            this.properties = new Properties();
        }

        /**
         * 生产者消息 key 序列化器
         * @param serializerClass
         * @return
         */
        public ProducerConfigBuilder keySerializer(Class<?> serializerClass) {
            properties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, serializerClass.getName());
            return this;
        }

        /**
         * 生产者消息 value 序列化器
         * @param serializerClass
         * @return
         */
        public ProducerConfigBuilder valueSerializer(Class<?> serializerClass) {
            properties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializerClass.getName());
            return this;
        }

        /**
         * 重试次数
         * @param retries
         * @return
         */
        public ProducerConfigBuilder retries(int retries){
            properties.setProperty(ProducerConfig.RETRIES_CONFIG, String.valueOf(retries));
            return this;
        }

        public enum Acks{
            /**
             * 发送消息后直接返回
             */
            None(0),
            /**
             * Leader 副本写入后返回
             */
            Leader(1),
            /**
             * ISR 副本写入后返回
             */
            ALL(-1);

            private Integer ack;

            Acks(Integer ack){
                this.ack = ack;
            }
        }

        public ProducerConfigBuilder acks(Acks acks){
            properties.setProperty(ProducerConfig.ACKS_CONFIG, String.valueOf(acks.ack));
            return this;
        }

        @Override
        public ProducerConfigBuilder id(String id){
            properties.setProperty(ProducerConfig.CLIENT_ID_CONFIG, id);
            return this;
        }

        @Override
        public ConfigurationBuilder interceptor(Class clazz) {

            if (clazz != ProducerInterceptor.class){
                throw new IllegalArgumentException("producer interceptor must be subclass of ProducerInterceptor !");
            }
            properties.setProperty(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, clazz.getName());
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
         * 设置消费者 Key 反序列化器
         * @param clazz
         * @return
         */
        public ConsumerConfigBuilder keyDeserializer(Class<? extends Deserializer> clazz){

            if (clazz == null){
                properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                return this;
            }
            properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, clazz.getName());
            return this;
        }

        /**
         * 设置消费者 Value 反序列化器
         * @param clazz
         * @return
         */
        public ConsumerConfigBuilder valueDeserializer(Class<? extends Deserializer> clazz){
            if (clazz == null){
                properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                return this;
            }
            properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, clazz.getName());
            return this;
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
         * 消费者拦截器配置
         * @param clazz
         * @return
         */
        public  ConsumerConfigBuilder interceptor(Class clazz){
            if (clazz != ConsumerInterceptor.class){
                throw new IllegalArgumentException("error");
            }
            properties.setProperty(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, clazz.getName());
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


    public static class AdminConfigBuilder extends ConfigurationBuilder{

        Properties properties;

        AdminConfigBuilder(String brokers){
            properties = new Properties();
            properties.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        }

        @Override
        public Properties build() {
            return properties;
        }

        @Override
        public AdminConfigBuilder brokers(String brokers) {
            properties.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
            return this;
        }

        @Override
        public AdminConfigBuilder id(String id) {
            return null;
        }

        @Override
        public ConfigurationBuilder interceptor(Class clazz) {
            return null;
        }
    }

}
