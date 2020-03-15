package example.coprocessor;

import org.apache.hadoop.hbase.CellScanner;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionServerCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionServerObserver;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.replication.ReplicationEndpoint;

import java.io.IOException;
import java.util.List;

/**
 * @author Administrator
 * @date 2020/3/15
 */
public class JMXRegionServerSource implements RegionServerObserver {
    @Override
    public void preStopRegionServer(ObserverContext<RegionServerCoprocessorEnvironment> env) throws IOException {

    }

    @Override
    public void preMerge(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB) throws IOException {

    }

    @Override
    public void postMerge(ObserverContext<RegionServerCoprocessorEnvironment> c, Region regionA, Region regionB, Region mergedRegion) throws IOException {

    }

    @Override
    public void preMergeCommit(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB, List<Mutation> metaEntries) throws IOException {

    }

    @Override
    public void postMergeCommit(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB, Region mergedRegion) throws IOException {

    }

    @Override
    public void preRollBackMerge(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB) throws IOException {

    }

    @Override
    public void postRollBackMerge(ObserverContext<RegionServerCoprocessorEnvironment> ctx, Region regionA, Region regionB) throws IOException {

    }

    @Override
    public void preRollWALWriterRequest(ObserverContext<RegionServerCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public void postRollWALWriterRequest(ObserverContext<RegionServerCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public ReplicationEndpoint postCreateReplicationEndPoint(ObserverContext<RegionServerCoprocessorEnvironment> ctx, ReplicationEndpoint endpoint) {
        return null;
    }

    @Override
    public void preReplicateLogEntries(ObserverContext<RegionServerCoprocessorEnvironment> ctx, List<AdminProtos.WALEntry> entries, CellScanner cells) throws IOException {

    }

    @Override
    public void postReplicateLogEntries(ObserverContext<RegionServerCoprocessorEnvironment> ctx, List<AdminProtos.WALEntry> entries, CellScanner cells) throws IOException {

    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {

    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {

    }
}
