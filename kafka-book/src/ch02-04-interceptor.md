# 拦截器
消费者拦截器主要在拉取消息和提交消费位移的时候进行定制化处理，定义消费拦截器需要实现 ```org.apache.kafka.clients.consumer.ConsumerInterceptor``` 接口并重写方法：
- ```onConsume(records)```：KafkaConsumer 在 poll 方法返回之前调用来对消息进行定制化操作，onConsume 方法中的异常将会被捕获而不会向上传递
- ```onCommit(offsets)```：KafkaConsumer 在提交完消费位移之后调用来记录跟踪提交的位移信息
```java
public class CustomConsumerInterceptor implements ConsumerInterceptor<String, String> {

    private static final long EXPIRE_INTERVAL = TimeUnit.SECONDS.toMillis(10);
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

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((tp, offset) -> System.out.println(tp + ":" + offset.offset()));
    }

    @Override
    public void close() {}

    @Override
    public void configure(Map<String, ?> configs) {}
}
```
在创建消费者实例时指定配置才能使自定义拦截器生效：
```java
props.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, CustomConsumerInterceptor.class.getName());
```