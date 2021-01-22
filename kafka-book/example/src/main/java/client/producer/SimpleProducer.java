package client.producer;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

public class SimpleProducer {

    public static final String brokerList = "localhost:19092";
    public static final String topic = "topic-demo";

    // 初始化生产者参数
    public static Properties initConfig(){
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    public static void main(String[] args){
        Properties props = initConfig();
        // 创建生产者
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        // 创建消息
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, "hello kafka");
        try{
            for(int i = 0; i < 10; i++){
                // 发送消息
                producer.send(record);
            }

//            producer.send(record, (metadata, exception) -> {
//
//            });
        }catch(Exception e){
            e.printStackTrace();
        }
        // 关闭生产者
        producer.close();
    }


}
