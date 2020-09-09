package example.api.admin;

import junit.framework.TestCase;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.junit.Test;

import java.io.IOException;

public class AdminApiTest extends TestCase {

    private AdminApi adminApi;

    public void setUp() throws IOException {
        adminApi = new AdminApi();
    }

    @Test
    public void testCreateTable() {

        adminApi.createTable("test_table", "test_family");

        final TableDescriptor tableDescriptor = adminApi.describe("test_table");
        assertTrue("test_table".equals(tableDescriptor.getTableName().getNameAsString()));
    }
}