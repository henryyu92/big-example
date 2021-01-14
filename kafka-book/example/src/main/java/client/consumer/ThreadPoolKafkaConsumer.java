package client.consumer;

import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

public class ThreadPoolKafkaConsumer {
  public static final String broker = "localhost:9092";
  public static final String topic = "topic-demo";
  public static final String groupId = "group-demo";
  public static final int threadNum = 10;
  private static final ExecutorService pool = new ThreadPoolExecutor(
      threadNum,
      threadNum,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue<>(1024),
      new ThreadPoolExecutor.AbortPolicy());

  public static Properties initConfig() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, "client-demo");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

    return props;
  }

  public static void main(String[] args) {
    Properties props = initConfig();
    KafkaConsumer<String, String> consumer = new KafkaConsumer<String, String>(props);
    consumer.subscribe(Arrays.asList(topic));
    try{

      while (true) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
        if (!records.isEmpty()){
          pool.submit(new RecordHandler(records));
        }
      }
    }catch (Exception e){
      e.printStackTrace();
    }finally {
      consumer.close();
    }
  }

  public static class RecordHandler extends Thread{
    public final ConsumerRecords<String, String> records;
    public RecordHandler(ConsumerRecords<String, String> records){
      this.records = records;
    }

    @Override
    public void run() {
      // process
    }
  }
}
