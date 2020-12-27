package example.api.client.serializer;

import org.apache.kafka.common.serialization.Serializer;

public class ProtobufSerializer<T> implements Serializer<T> {

    @Override
    public byte[] serialize(String topic, T data) {
        return new byte[0];
    }
}
