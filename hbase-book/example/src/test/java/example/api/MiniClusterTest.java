package example.api;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Integration Testing with an HBase Mini-Cluster
 */
public class MiniClusterTest {

    public static HBaseTestingUtility utility;
    byte[] CF = "CF".getBytes();
    byte[] CQ1 = "CQ-1".getBytes();
    byte[] CQ2 = "CQ-2".getBytes();

    @Before
    public void setup() throws Exception {
        utility = new HBaseTestingUtility();
        utility.startMiniCluster();
    }

    @Test
    public void testInsert() throws IOException {
        Table table = utility.createTable(TableName.valueOf("MyTest"), CF);
        Put put = new Put(Bytes.toBytes("ROWKEY-1"));
        put.addColumn(CF, CQ1, Bytes.toBytes("DATA-1"));
        put.addColumn(CF, CQ2, Bytes.toBytes("DATA-2"));
        table.put(put);

        Get get1 = new Get(Bytes.toBytes("ROWKEY-1"));
        get1.addColumn(CF, CQ1);
        Result result1 = table.get(get1);
        assertEquals(Bytes.toString(result1.getRow()), "ROKWKEY-1");
        assertEquals(Bytes.toString(result1.value()), "DATA-1");

        Get get2 = new Get(Bytes.toBytes("ROWKEY-1"));
        get2.addColumn(CF, CQ2);
        Result result2 = table.get(get2);
        assertEquals(Bytes.toString(result2.getRow()), "ROWKEY-1");
        assertEquals(Bytes.toString(result2.value()), "DATA-2");
    }

}
