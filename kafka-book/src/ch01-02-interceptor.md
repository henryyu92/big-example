## 拦截器

生产者拦截器在消息发送前以及消息发送完成后作用，可用于对发送的消息以及返回的结果作统一的处理。Kafka 提供了 `ProducerInterceptor` 接口定义生产者拦截器，该接口定义了三个方法：
```java
// 在 send 方法之后，消息系列化和分区之前调用
// 可以在这个方法中修改消息，消息修改后会导致后续的操作作用于新的消息
// 方法中抛出的异常会被 catch 而不会继续向上层抛出
public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record);

// 收到消息确认或者消息发送失败之后调用
// 在 callback 之前调用，方法抛出的异常会被忽略
// 方法运行在 I/O 线程，因此方法的实现需要尽可能简单
public void onAcknowledgement(RecordMetadata metadata, Exception exception);

// 关闭拦截器时调用
public void close();
```
`ProducerInterceptor` 接口同时继承了 `Configurable` 接口，可以通过 `configure` 方法获取客户端配置的参数。生产者拦截器需要在创建生产者客户端实例时设置，在创建实例的配置中添加配置：
```java
properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "interceptor.class.name");
```