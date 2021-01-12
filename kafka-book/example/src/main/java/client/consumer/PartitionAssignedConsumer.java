package client.consumer;

import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

public class PartitionAssignedConsumer {

  private static final String BROKER = System.getProperty("broker", "localhost:19092");


  public static void main(String[] args) {

    Properties properties = new Properties();
    properties.setProperty("bootstrap.servers", BROKER);
    properties.setProperty("key.deserializer", StringDeserializer.class.getName());
    properties.setProperty("value.deserializer", StringDeserializer.class.getName());
    properties.setProperty("group.id", "partition-assigned-group");

    KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(properties);
    List<PartitionInfo> partitionInfos = consumer.partitionsFor("topic-hello");
    if (partitionInfos.isEmpty()){
      return;
    }
    consumer.assign(Collections.singleton(new TopicPartition("topic-hello", (partitionInfos.get(0).partition()))));

  }

}
