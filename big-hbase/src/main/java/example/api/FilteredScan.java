package example.api;


import example.miniBase.Bytes;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.*;

public class FilteredScan {


    public void familyFilter(){
        Scan scan = new Scan();
        FamilyFilter ff = new FamilyFilter(CompareOperator.NOT_EQUAL, new BinaryComparator(Bytes.toBytes("c")));
        ColumnRangeFilter qf = new ColumnRangeFilter(Bytes.toBytes("a"), true, Bytes.toBytes("b"), true);
        FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL, ff, qf);
        scan.setFilter(filterList);
    }

}
