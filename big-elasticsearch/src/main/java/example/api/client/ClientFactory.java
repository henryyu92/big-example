package example.api.client;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;

import java.io.IOException;


public class ClientFactory {

    public static RestHighLevelClient restHighLevelClient(){
        /**
         * The high-level client will internally create the low-level client used to perform requests based on the provided builder.
         *
         * That low-level client maintains a pool of connections and starts some threads so you should close the high-level client when you are well and truly done with it
         */
        return new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost("localhost", 9200, "http")
                )
         );
    }

    public RestClient getRestClient(String hostname, int port){
        RestClientBuilder builder = RestClient.builder(new HttpHost(hostname, port));

        Header[] headers = new Header[]{
                new BasicHeader("header", "value"),
        };

        builder.setDefaultHeaders(headers);

        return builder.build();
    }
}
