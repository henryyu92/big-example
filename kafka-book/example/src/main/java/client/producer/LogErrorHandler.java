package client.producer;


import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.util.logging.Logger;

public class LogErrorHandler implements Callback {

    private Logger logger = Logger.getLogger(LogErrorHandler.class.getName());

    @Override
    public void onCompletion(RecordMetadata metadata, Exception exception) {
        if (exception != null){
            // todo
        }
    }
}
