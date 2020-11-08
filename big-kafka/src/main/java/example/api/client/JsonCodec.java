package example.api.client;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public interface JsonCodec<T> extends Serializer<T>, Deserializer<T> {


    @Override
    default void configure(Map<String, ?> configs, boolean isKey) {

    }

    @Override
    default void close() {

    }
}
