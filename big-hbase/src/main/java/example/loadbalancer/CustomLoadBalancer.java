package example.loadbalancer;

import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer;

import java.util.List;
import java.util.Map;

/**
 * @author Administrator
 * @date 2020/3/24
 */
public class CustomLoadBalancer extends BaseLoadBalancer {
    @Override
    public List<RegionPlan> balanceCluster(Map<ServerName, List<HRegionInfo>> clusterState) throws HBaseIOException {
        return null;
    }
}
