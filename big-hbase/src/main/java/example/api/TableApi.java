package example.api;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;
import java.util.Iterator;
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
        Connection conn = getConnection();
        try(Table t = conn.getTable(TableName.valueOf(table))){
            Get get = new Get(rowkey.getBytes());
            Result result = t.get(get);
            if (result != null){
                Cell cell = result.current();
                System.out.println(new String(cell.getValueArray()));
            }
        }catch (IOException e){
            e.printStackTrace();
        }
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

    public void scan(String table, String startKey, String stopKey){

    }

    public void limitedScan(){

        Connection conn = getConnection();

        try(Table table = conn.getTable(TableName.valueOf("test"))){
            Scan scan = new Scan()
                    .withStartRow("startRow".getBytes())
                    .withStopRow("stopRow".getBytes())
                    .setCaching(1000)
                    .setBatch(10)
                    .setMaxResultSize(-1);
            ResultScanner scanner = table.getScanner(scan);
            Iterator<Result> it = scanner.iterator();
            while (it.hasNext()){
                Result next = it.next();
                System.out.println(next);
            }
        }catch (IOException e){
            e.printStackTrace();
        }
    }
}
