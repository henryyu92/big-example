package example.api.admin;

import example.api.BaseApi;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 表的创建、修改、全限操作由 Master 处理
 */
public class AdminApi extends BaseApi {


    public AdminApi() throws IOException {
        super();
    }

    public AdminApi(Configuration conf) throws IOException {
        super(conf);
    }


    public void createTable(String tableName, String... columnFamilyName) {
        Connection conn = getConnection();

        try(Admin admin = conn.getAdmin()){



            TableDescriptor tableDescriptor = TableDescriptorBuilder
                    .newBuilder(TableName.valueOf(tableName))
                    .setColumnFamilies(Stream.of(columnFamilyName).map(familyName ->
                        ColumnFamilyDescriptorBuilder.newBuilder(Bytes.toBytes(familyName)).build())
                            .collect(Collectors.toList()))
                    .setCompactionEnabled(true)
                    .build();

            admin.createTable(tableDescriptor);
        }catch (IOException e){

        }

    }

    /**
     * 查看表的描述信息
     * @param tableName
     */
    public TableDescriptor describe(String tableName){
        Connection conn = getConnection();

        try(Admin admin = conn.getAdmin()){

            TableDescriptor descriptor = admin.getDescriptor(TableName.valueOf(tableName));

            System.out.println(descriptor);

            return descriptor;

        }catch (IOException e){

            return null;
        }
    }
}
