package example.consumer;


import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TTL
 */
public class TtlInterceptor implements ConsumerInterceptor<String, String> {

    private static final long EXPIRE_INTERVAL = TimeUnit.SECONDS.toMillis(10);

    /**
     * 在 poll 方法返回之前调用来对消息进行定制化操作，方法中的异常将会被捕获而不会向上传递
     * @param records
     * @return
     */
    @Override
    public ConsumerRecords<String, String> onConsume(ConsumerRecords<String, String> records) {
        long now = System.currentTimeMillis();
        Map<TopicPartition, List<ConsumerRecord<String, String>>> newRecords = new HashMap<>();

        for(TopicPartition tp : records.partitions()){
            List<ConsumerRecord<String, String>> tpRecords = records.records(tp);
            List<ConsumerRecord<String, String>> newTpRecords = new ArrayList<>();
            for(ConsumerRecord<String, String> record : tpRecords){
                if (now - record.timestamp() < EXPIRE_INTERVAL){
                    newTpRecords.add(record);
                }
            }
            if (!newTpRecords.isEmpty()){
                newRecords.put(tp, newTpRecords);
            }
        }
        return new ConsumerRecords<>(newRecords);
    }

    /**
     * 在提交完消费位移之后调用来记录跟踪提交的位移信息
     * @param offsets
     */
    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((tp, offset) -> System.out.println(tp + ":" + offset.offset()));
    }

    @Override
    public void close() {

    }

    @Override
    public void configure(Map<String, ?> configs) {

    }
}
