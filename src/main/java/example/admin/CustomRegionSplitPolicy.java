package example.admin;

import org.apache.hadoop.hbase.regionserver.RegionSplitPolicy;

/**
 * @author Administrator
 * @date 2020/3/24
 */
public class CustomRegionSplitPolicy extends RegionSplitPolicy {
    @Override
    protected boolean shouldSplit() {
        return false;
    }
}
