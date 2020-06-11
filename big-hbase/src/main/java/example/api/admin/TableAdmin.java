package example.api.admin;

import example.api.BaseApi;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;

import java.io.IOException;

/**
 * 表的创建、修改、全限操作由 Master 处理
 */
public class TableAdmin extends BaseApi {


    public TableAdmin() throws IOException {
        super();
    }

    public TableAdmin(Configuration conf) throws IOException {
        super(conf);
    }


    public void createTable(String tableName) throws IOException {
        Connection conn = getConnection();

        Admin admin = conn.getAdmin();

        TableDescriptor tableDescriptor = TableDescriptorBuilder
                .newBuilder(TableName.valueOf(tableName))
                .setColumnFamily(ColumnFamilyDescriptorBuilder.newBuilder("cf".getBytes()).build())
                .setCompactionEnabled(true)
                .build();

        admin.createTable(tableDescriptor);
    }
}
