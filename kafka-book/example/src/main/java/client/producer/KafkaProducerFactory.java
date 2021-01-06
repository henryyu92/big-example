package client.producer;

import org.apache.kafka.clients.producer.KafkaProducer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KafkaProducerFactory {

    private KafkaProducerFactory(){}

    private static final Map<Entry, KafkaProducer> producerPool = new ConcurrentHashMap<>();

    private static class Entry<K, V>{
        K key;
        V value;

        public Entry(K key, V value){
            this.key = key;
            this.value = value;
        }
    }


    public static <K, V> KafkaProducer<K, V> getProducer(String topic, K key, V value){

        Entry<K, V> entry = new Entry<>(key, value);
        KafkaProducer kafkaProducer = producerPool.get(entry);
        synchronized (KafkaProducerFactory.class){
            if (kafkaProducer == null){
//                producerPool.put()
                return null;
            }
            return kafkaProducer;
        }
    }

    public static void releaseProducer(KafkaProducer producer){
    }
}
