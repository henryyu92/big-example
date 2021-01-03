package client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigurationFactory{

    public static Properties getProducerConfiguration(String name) throws IOException {

        Properties properties = new Properties();
        try(InputStream stream = ClassLoader.getSystemClassLoader().getResourceAsStream(name);){
            properties.load(stream);
        }
        return properties;
    }

}