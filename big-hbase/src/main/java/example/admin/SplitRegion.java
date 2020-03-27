package example.admin;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import java.io.IOException;

/**
 * @author Administrator
 * @date 2020/3/24
 */
public class SplitRegion {

    public static void main(String[] args) {

        try {
            Connection conn = ConnectionFactory.createConnection();

            Admin admin = conn.getAdmin();

            admin.split(TableName.valueOf("tableName"));


        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
