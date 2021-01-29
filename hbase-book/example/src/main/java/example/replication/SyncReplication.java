package example.replication;

import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import java.io.IOException;

public class SyncReplication {

    public static void main(String[] args) {

        try {
            Connection conn = ConnectionFactory.createConnection();
            Admin admin = conn.getAdmin();


        } catch (IOException e) {
            e.printStackTrace();
        }


    }
}
