## 拦截器
消费者拦截器主要在拉取消息和提交消费位移的时候进行定制化处理，Kafka 提供了 `ConsumerInterceptor` 接口定义消费者拦截器，实现接口并重写接口定义的方法即可自定义消费者拦截器逻辑：
```java
// 消息在 poll 返回前调用，可以对消息进行定制化处理，处理过程中的异常会 catch 住而不会继续向上次抛出
public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records);

// 消费者提交完位移后调用
public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets);
```
消费者拦截器需要在创建消费者端时显式指定，Kafka 消费者允许设置多个消费者拦截器，其调用的顺序和设置的顺序相同，当其中某个拦截器异常时不会影响后续拦截器的处理。
```java
properties.setProperty("interceptor.classes", "interceptor1.class");
properties.setProperty("interceptor.classes", "interceptor2.class");
```