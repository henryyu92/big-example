package example.coprocessor;

import com.google.common.collect.ImmutableList;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.filter.ByteArrayComparable;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.io.FSDataInputStreamWrapper;
import org.apache.hadoop.hbase.io.Reference;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.regionserver.*;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.wal.WALKey;

import java.io.IOException;
import java.util.List;
import java.util.NavigableSet;

/**
 * @author Administrator
 * @date 2020/3/15
 */
public class JMXRegionSource implements RegionObserver {
    @Override
    public void preOpen(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException {

    }

    @Override
    public void postOpen(ObserverContext<RegionCoprocessorEnvironment> c) {

    }

    @Override
    public void postLogReplay(ObserverContext<RegionCoprocessorEnvironment> c) {

    }

    @Override
    public InternalScanner preFlushScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store, KeyValueScanner memstoreScanner, InternalScanner s) throws IOException {
        return null;
    }

    @Override
    public void preFlush(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException {

    }

    @Override
    public InternalScanner preFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store, InternalScanner scanner) throws IOException {
        return null;
    }

    @Override
    public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException {

    }

    @Override
    public void postFlush(ObserverContext<RegionCoprocessorEnvironment> c, Store store, StoreFile resultFile) throws IOException {

    }

    @Override
    public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store, List<StoreFile> candidates, CompactionRequest request) throws IOException {

    }

    @Override
    public void preCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store, List<StoreFile> candidates) throws IOException {

    }

    @Override
    public void postCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store, ImmutableList<StoreFile> selected, CompactionRequest request) {

    }

    @Override
    public void postCompactSelection(ObserverContext<RegionCoprocessorEnvironment> c, Store store, ImmutableList<StoreFile> selected) {

    }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store, InternalScanner scanner, ScanType scanType, CompactionRequest request) throws IOException {
        return null;
    }

    @Override
    public InternalScanner preCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store, InternalScanner scanner, ScanType scanType) throws IOException {
        return null;
    }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store, List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs, InternalScanner s, CompactionRequest request) throws IOException {
        return null;
    }

    @Override
    public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store, List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs, InternalScanner s) throws IOException {
        return null;
    }

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store, StoreFile resultFile, CompactionRequest request) throws IOException {

    }

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> c, Store store, StoreFile resultFile) throws IOException {

    }

    @Override
    public void preSplit(ObserverContext<RegionCoprocessorEnvironment> c) throws IOException {

    }

    @Override
    public void preSplit(ObserverContext<RegionCoprocessorEnvironment> c, byte[] splitRow) throws IOException {

    }

    @Override
    public void postSplit(ObserverContext<RegionCoprocessorEnvironment> c, Region l, Region r) throws IOException {

    }

    @Override
    public void preSplitBeforePONR(ObserverContext<RegionCoprocessorEnvironment> ctx, byte[] splitKey, List<Mutation> metaEntries) throws IOException {

    }

    @Override
    public void preSplitAfterPONR(ObserverContext<RegionCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public void preRollBackSplit(ObserverContext<RegionCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public void postRollBackSplit(ObserverContext<RegionCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public void postCompleteSplit(ObserverContext<RegionCoprocessorEnvironment> ctx) throws IOException {

    }

    @Override
    public void preClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested) throws IOException {

    }

    @Override
    public void postClose(ObserverContext<RegionCoprocessorEnvironment> c, boolean abortRequested) {

    }

    @Override
    public void preGetClosestRowBefore(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, Result result) throws IOException {

    }

    @Override
    public void postGetClosestRowBefore(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, Result result) throws IOException {

    }

    @Override
    public void preGetOp(ObserverContext<RegionCoprocessorEnvironment> c, Get get, List<Cell> result) throws IOException {

    }

    @Override
    public void postGetOp(ObserverContext<RegionCoprocessorEnvironment> c, Get get, List<Cell> result) throws IOException {

    }

    @Override
    public boolean preExists(ObserverContext<RegionCoprocessorEnvironment> c, Get get, boolean exists) throws IOException {
        return false;
    }

    @Override
    public boolean postExists(ObserverContext<RegionCoprocessorEnvironment> c, Get get, boolean exists) throws IOException {
        return false;
    }

    @Override
    public void prePut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit, Durability durability) throws IOException {

    }

    @Override
    public void postPut(ObserverContext<RegionCoprocessorEnvironment> c, Put put, WALEdit edit, Durability durability) throws IOException {

    }

    @Override
    public void preDelete(ObserverContext<RegionCoprocessorEnvironment> c, Delete delete, WALEdit edit, Durability durability) throws IOException {

    }

    @Override
    public void prePrepareTimeStampForDeleteVersion(ObserverContext<RegionCoprocessorEnvironment> c, Mutation mutation, Cell cell, byte[] byteNow, Get get) throws IOException {

    }

    @Override
    public void postDelete(ObserverContext<RegionCoprocessorEnvironment> c, Delete delete, WALEdit edit, Durability durability) throws IOException {

    }

    @Override
    public void preBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c, MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {

    }

    @Override
    public void postBatchMutate(ObserverContext<RegionCoprocessorEnvironment> c, MiniBatchOperationInProgress<Mutation> miniBatchOp) throws IOException {

    }

    @Override
    public void postStartRegionOperation(ObserverContext<RegionCoprocessorEnvironment> ctx, Region.Operation operation) throws IOException {

    }

    @Override
    public void postCloseRegionOperation(ObserverContext<RegionCoprocessorEnvironment> ctx, Region.Operation operation) throws IOException {

    }

    @Override
    public void postBatchMutateIndispensably(ObserverContext<RegionCoprocessorEnvironment> ctx, MiniBatchOperationInProgress<Mutation> miniBatchOp, boolean success) throws IOException {

    }

    @Override
    public boolean preCheckAndPut(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Put put, boolean result) throws IOException {
        return false;
    }

    @Override
    public boolean preCheckAndPutAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Put put, boolean result) throws IOException {
        return false;
    }

    @Override
    public boolean postCheckAndPut(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Put put, boolean result) throws IOException {
        return false;
    }

    @Override
    public boolean preCheckAndDelete(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Delete delete, boolean result) throws IOException {
        return false;
    }

    @Override
    public boolean preCheckAndDeleteAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Delete delete, boolean result) throws IOException {
        return false;
    }

    @Override
    public boolean postCheckAndDelete(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, CompareFilter.CompareOp compareOp, ByteArrayComparable comparator, Delete delete, boolean result) throws IOException {
        return false;
    }

    @Override
    public long preIncrementColumnValue(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL) throws IOException {
        return 0;
    }

    @Override
    public long postIncrementColumnValue(ObserverContext<RegionCoprocessorEnvironment> c, byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL, long result) throws IOException {
        return 0;
    }

    @Override
    public Result preAppend(ObserverContext<RegionCoprocessorEnvironment> c, Append append) throws IOException {
        return null;
    }

    @Override
    public Result preAppendAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c, Append append) throws IOException {
        return null;
    }

    @Override
    public Result postAppend(ObserverContext<RegionCoprocessorEnvironment> c, Append append, Result result) throws IOException {
        return null;
    }

    @Override
    public Result preIncrement(ObserverContext<RegionCoprocessorEnvironment> c, Increment increment) throws IOException {
        return null;
    }

    @Override
    public Result preIncrementAfterRowLock(ObserverContext<RegionCoprocessorEnvironment> c, Increment increment) throws IOException {
        return null;
    }

    @Override
    public Result postIncrement(ObserverContext<RegionCoprocessorEnvironment> c, Increment increment, Result result) throws IOException {
        return null;
    }

    @Override
    public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan, RegionScanner s) throws IOException {
        return null;
    }

    @Override
    public KeyValueScanner preStoreScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store, Scan scan, NavigableSet<byte[]> targetCols, KeyValueScanner s) throws IOException {
        return null;
    }

    @Override
    public RegionScanner postScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Scan scan, RegionScanner s) throws IOException {
        return null;
    }

    @Override
    public boolean preScannerNext(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s, List<Result> result, int limit, boolean hasNext) throws IOException {
        return false;
    }

    @Override
    public boolean postScannerNext(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s, List<Result> result, int limit, boolean hasNext) throws IOException {
        return false;
    }

    @Override
    public boolean postScannerFilterRow(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s, byte[] currentRow, int offset, short length, boolean hasMore) throws IOException {
        return false;
    }

    @Override
    public void preScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s) throws IOException {

    }

    @Override
    public void postScannerClose(ObserverContext<RegionCoprocessorEnvironment> c, InternalScanner s) throws IOException {

    }

    @Override
    public void preWALRestore(ObserverContext<? extends RegionCoprocessorEnvironment> ctx, HRegionInfo info, WALKey logKey, WALEdit logEdit) throws IOException {

    }

    @Override
    public void preWALRestore(ObserverContext<RegionCoprocessorEnvironment> ctx, HRegionInfo info, HLogKey logKey, WALEdit logEdit) throws IOException {

    }

    @Override
    public void postWALRestore(ObserverContext<? extends RegionCoprocessorEnvironment> ctx, HRegionInfo info, WALKey logKey, WALEdit logEdit) throws IOException {

    }

    @Override
    public void postWALRestore(ObserverContext<RegionCoprocessorEnvironment> ctx, HRegionInfo info, HLogKey logKey, WALEdit logEdit) throws IOException {

    }

    @Override
    public void preBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx, List<Pair<byte[], String>> familyPaths) throws IOException {

    }

    @Override
    public boolean postBulkLoadHFile(ObserverContext<RegionCoprocessorEnvironment> ctx, List<Pair<byte[], String>> familyPaths, boolean hasLoaded) throws IOException {
        return false;
    }

    @Override
    public StoreFile.Reader preStoreFileReaderOpen(ObserverContext<RegionCoprocessorEnvironment> ctx, FileSystem fs, Path p, FSDataInputStreamWrapper in, long size, CacheConfig cacheConf, Reference r, StoreFile.Reader reader) throws IOException {
        return null;
    }

    @Override
    public StoreFile.Reader postStoreFileReaderOpen(ObserverContext<RegionCoprocessorEnvironment> ctx, FileSystem fs, Path p, FSDataInputStreamWrapper in, long size, CacheConfig cacheConf, Reference r, StoreFile.Reader reader) throws IOException {
        return null;
    }

    @Override
    public Cell postMutationBeforeWAL(ObserverContext<RegionCoprocessorEnvironment> ctx, MutationType opType, Mutation mutation, Cell oldCell, Cell newCell) throws IOException {
        return null;
    }

    @Override
    public DeleteTracker postInstantiateDeleteTracker(ObserverContext<RegionCoprocessorEnvironment> ctx, DeleteTracker delTracker) throws IOException {
        return null;
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {

    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {

    }
}
