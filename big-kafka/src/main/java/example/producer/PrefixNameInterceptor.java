package example.producer;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class PrefixNameInterceptor implements ProducerInterceptor<String, String> {

    private AtomicLong sendSuccess = new AtomicLong(0);
    private AtomicLong sendFailure = new AtomicLong(0);

    /**
     * 发送 Message 到 Broker 之前调用，在 序列化器 以及 分区器 之前调用
     * @param record
     * @return
     */
    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        String newValue = "prefix_" + record.value();
        return new ProducerRecord<>(
                record.topic(),
                record.partition(),
                record.timestamp(),
                record.key(),
                newValue,
                record.headers());
    }

    /**
     * Broker 返回 ack 时调用
     * @param metadata
     * @param exception
     */
    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (exception == null){
            sendSuccess.incrementAndGet();
        }else{
            sendFailure.incrementAndGet();
        }
    }

    /**
     * Producer 关闭时调用
     */
    @Override
    public void close() {
        double successRatio = sendSuccess.doubleValue() / (sendSuccess.doubleValue() + sendFailure.doubleValue());
        System.out.println("发送成功率；" + successRatio);
    }

    @Override
    public void configure(Map<String, ?> configs) {

    }
}
