package example.api;

import example.miniBase.Bytes;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.ColumnRangeFilter;
import org.apache.hadoop.hbase.filter.FamilyFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PrefixFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * HBase Table API
 *
 * Table 是线程不安全的，每个线程需要独立创建 Table，HBase 创建 Table 时需要指定 ColumnFamily
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

    public void get(String table, String row){
        Connection conn = getConnection();
        try(Table t = conn.getTable(TableName.valueOf(table))){
            Get get = new Get(row.getBytes());
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

    public void scan(String table, String startKey, String stopKey) throws IOException {
        Scan scan = new Scan();

        Connection connection = getConnection();
        Table t = connection.getTable(TableName.valueOf(table));

        ResultScanner scanner = t.getScanner(scan);
        for (Result r = scanner.next(); r != null ; r = scanner.next()){

        }
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

    public void familyFilter(){
        Scan scan = new Scan();
        FamilyFilter ff = new FamilyFilter(CompareOperator.NOT_EQUAL, new BinaryComparator(Bytes.toBytes("c")));
        ColumnRangeFilter qf = new ColumnRangeFilter(Bytes.toBytes("a"), true, Bytes.toBytes("b"), true);

        ColumnPrefixFilter cpf = new ColumnPrefixFilter("prefix".getBytes());


        FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL, ff, qf);
        scan.setFilter(filterList);
    }
}
