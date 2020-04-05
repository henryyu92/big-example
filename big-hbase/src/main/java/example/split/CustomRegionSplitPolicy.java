package example.split;

import org.apache.hadoop.hbase.regionserver.RegionSplitPolicy;

public class CustomRegionSplitPolicy extends RegionSplitPolicy {
    @Override
    protected boolean shouldSplit() {
        return false;
    }
}
