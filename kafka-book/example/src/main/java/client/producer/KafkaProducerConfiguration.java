package client.producer;

public class KafkaProducerConfiguration<K, V> {

    private KafkaProducerConfiguration(){}

    private String brokers;
    private Class<K> keySerializerClass;
    private Class<V> valueSerializerClass;

    private Long maxRequestSize;
}
