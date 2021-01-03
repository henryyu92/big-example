package client.producer.serializer;

import org.apache.kafka.common.serialization.Serializer;

public class ProtoBufSerializer<T> implements Serializer<T> {

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null){
            return null;
        }

        return new byte[0];
    }
}
