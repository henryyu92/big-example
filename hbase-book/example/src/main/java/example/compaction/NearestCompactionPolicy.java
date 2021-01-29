package example.compaction;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.regionserver.HStoreFile;
import org.apache.hadoop.hbase.regionserver.StoreConfigInformation;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionConfiguration;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionPolicy;

import java.io.IOException;
import java.util.Collection;


public class NearestCompactionPolicy extends CompactionPolicy {


    public NearestCompactionPolicy(Configuration conf, StoreConfigInformation storeConfigInfo) {
        super(conf, storeConfigInfo);
    }

    @Override
    public boolean shouldPerformMajorCompaction(Collection<HStoreFile> collection) throws IOException {
        return false;
    }

    @Override
    public boolean throttleCompaction(long l) {
        return false;
    }
}
