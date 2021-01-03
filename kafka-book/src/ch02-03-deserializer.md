# 反序列化
KafkaConsumer 提供了多种反序列化器，且都实现了 Deserializer 接口，自定义反序列化器只需要实现 Deserializer 接口并实现方法，然后在消费者客户端配置相应参数即可：
```java
public class CustomDeserializer implements Deserializer<String> {
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {}

    @Override
    public String deserialize(String topic, byte[] data) { return new String(data); }

    @Override
    public void close() {}
}
```
需要在创建消费者客户端时指定反序列化类才能使自定义反序列化生效：
```java
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, CustomDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, CustomDeserializer.class.getName());
```