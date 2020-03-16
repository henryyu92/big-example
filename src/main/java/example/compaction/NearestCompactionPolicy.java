package example.compaction;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.regionserver.StoreConfigInformation;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionPolicy;

import java.io.IOException;
import java.util.Collection;

/**
 * @author Administrator
 * @date 2020/3/16
 */
public class NearestCompactionPolicy extends CompactionPolicy {

    public NearestCompactionPolicy(Configuration conf, StoreConfigInformation storeConfigInfo) {
        super(conf, storeConfigInfo);
    }

    @Override
    public boolean isMajorCompaction(Collection<StoreFile> filesToCompact) throws IOException {
        return false;
    }

    @Override
    public boolean throttleCompaction(long compactionSize) {
        return false;
    }
}
