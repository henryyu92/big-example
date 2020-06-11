package example.api;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import java.io.IOException;

public class BaseApi {

    private Connection conn;

    public BaseApi() throws IOException {
        conn = ConnectionFactory.createConnection();
    }

    public BaseApi(Configuration conf) throws IOException {
        conn = ConnectionFactory.createConnection(conf);
    }

    public Connection getConnection() {
        return conn;
    }

    public void closeConnection(){
        if (conn != null){
            try {
                conn.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
