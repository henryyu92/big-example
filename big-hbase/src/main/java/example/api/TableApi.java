package example.api;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;

import java.io.IOException;
import java.util.Map;

/**
 * HBase Table API
 */
public class TableApi {

    private Connection conn;

    public TableApi() throws IOException {
        conn = ConnectionFactory.createConnection();
    }

    public TableApi(Configuration conf) throws IOException {
        conn = ConnectionFactory.createConnection(conf);
    }


    public void put(String table, String key, String family, String qualifier, String value) {
        Put put = new Put(key.getBytes())
                .addColumn(family.getBytes(), qualifier.getBytes(), value.getBytes());

        try(Table t = conn.getTable(TableName.valueOf(table))){
            t.put(put);
        }catch (IOException ex){
            ex.printStackTrace();
        }
    }


    public void batchPut(String table, Map<String, String> map){

    }
}
