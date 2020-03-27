package example.admin;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.TimeUnit;


public class MergeRegion {

    public static void main(String[] args) {

        try {
            Connection conn = ConnectionFactory.createConnection();

            Admin admin = conn.getAdmin();

            admin.mergeRegions("regionA".getBytes(), "regionB".getBytes(), false);

            // wait a minute
            TimeUnit.SECONDS.sleep(TimeUnit.MINUTES.toMillis(1));

            List<HRegionInfo> regionInfos = admin.getTableRegions(TableName.valueOf("tableName"));

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
