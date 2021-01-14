package client.consumer;

import java.time.Duration;
import java.util.Collection;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public class ConsumerThread<K, V> implements Runnable {

  private final KafkaConsumer<K, V> consumer;
  private volatile boolean running;

  public ConsumerThread(String broker, String group, Collection<String> topics, K keyDeserializer, V valueDeserializer){
    Properties properties = new Properties();
    properties.setProperty("bootstrap.servers", broker);
    properties.setProperty("group.id", group);
    properties.setProperty("key.deserializer", keyDeserializer.getClass().getName());
    properties.setProperty("value.deserializer", valueDeserializer.getClass().getName());

    consumer = new KafkaConsumer<K, V>(properties);
    consumer.subscribe(topics);
  }

  @Override
  public void run() {
    while (running){
      ConsumerRecords<K, V> records = consumer.poll(Duration.ofSeconds(10));
    }
  }

  public void close(){
    running = false;
  }
}
