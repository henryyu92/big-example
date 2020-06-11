package example.api;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;
import java.util.Map;

/**
 * HBase Table API
 */
public class TableApi extends BaseApi {


    public TableApi() throws IOException {
        super();
    }

    public TableApi(Configuration conf) throws IOException {
        super(conf);
    }

    public void put(String table, String key, String family, String qualifier, String value) {

        Connection conn = getConnection();

        try(Table t = conn.getTable(TableName.valueOf(table))){

            Put put = new Put(key.getBytes())
                    .addColumn(family.getBytes(), qualifier.getBytes(), value.getBytes());
            t.put(put);
        }catch (IOException ex){
            ex.printStackTrace();
        }
    }


    public void batchPut(String table, Map<String, String> map){

    }

    public void get(String table, String rowkey){

    }

    public void scan(String table, String startKey, String stopKey){

    }

    public void delete(String table, String rowKey){
        Connection conn = getConnection();

        try(Table t = conn.getTable(TableName.valueOf(table))){

            Delete delete = new Delete(rowKey.getBytes());
            t.delete(delete);

        }catch (IOException e){
            e.printStackTrace();
        }
    }
}
