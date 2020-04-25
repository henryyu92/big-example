package example.datamodel;


import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;
import java.util.Iterator;

public class FilteredScan {

    public static void main(String[] args) throws IOException {


        Connection conn = ConnectionFactory.createConnection();

        Table table = conn.getTable(TableName.valueOf("test"));

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
    }

}
