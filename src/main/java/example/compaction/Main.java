package example.compaction;

import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.regionserver.DefaultStoreEngine;
import org.apache.hadoop.hbase.regionserver.compactions.FIFOCompactionPolicy;

import java.io.IOException;

/**
 * @author Administrator
 * @date 2020/3/19
 */
public class Main {

    public static void main(String[] args) {

        try {
            Connection conn = ConnectionFactory.createConnection();

            HTableDescriptor desc = conn.getAdmin().getTableDescriptor(TableName.valueOf(""));
            desc.setConfiguration(DefaultStoreEngine.DEFAULT_COMPACTOR_CLASS_KEY, FIFOCompactionPolicy.class.getName());

            HColumnDescriptor colDesc = desc.getFamily("family".getBytes());
            colDesc.setConfiguration(DefaultStoreEngine.DEFAULT_COMPACTOR_CLASS_KEY, FIFOCompactionPolicy.class.getName());

        } catch (IOException e) {
            e.printStackTrace();
        }



    }
}
