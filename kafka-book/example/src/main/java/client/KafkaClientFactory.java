package client;

import org.apache.kafka.clients.producer.KafkaProducer;

public class KafkaClientFactory {

    public <K, V> KafkaProducer<K, V> getProducerClient(Class<K> keySerializer, Class<V> valueSerializer){

    }
}
