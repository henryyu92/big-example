package example.api.client;

import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public class ProtobufCodec<T> implements Serializer<T>, Deserializer<T> {
    @Override
    public T deserialize(String topic, byte[] data) {
        return null;
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        return null;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {

    }

    @Override
    public byte[] serialize(String topic, T data) {
        return new byte[0];
    }

    @Override
    public void close() {

    }
}
