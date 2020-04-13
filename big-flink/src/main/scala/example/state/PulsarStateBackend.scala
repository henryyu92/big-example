//package example.state
//
//import java.util
//
//import org.apache.flink.api.common.JobID
//import org.apache.flink.api.common.typeutils.TypeSerializer
//import org.apache.flink.core.fs.CloseableRegistry
//import org.apache.flink.metrics.MetricGroup
//import org.apache.flink.runtime.execution.Environment
//import org.apache.flink.runtime.query.TaskKvStateRegistry
//import org.apache.flink.runtime.state.ttl.TtlTimeProvider
//import org.apache.flink.runtime.state._
//
//
//class PulsarStateBackend extends StateBackend{
//  override def resolveCheckpoint(externalPointer: String): CompletedCheckpointStorageLocation = {
//
//  }
//
//  override def createCheckpointStorage(jobId: JobID): CheckpointStorage = {
//
//  }
//
//  override def createKeyedStateBackend[K](env: Environment, jobID: JobID, operatorIdentifier: String, keySerializer: TypeSerializer[K], numberOfKeyGroups: Int, keyGroupRange: KeyGroupRange, kvStateRegistry: TaskKvStateRegistry, ttlTimeProvider: TtlTimeProvider, metricGroup: MetricGroup, stateHandles: util.Collection[KeyedStateHandle], cancelStreamRegistry: CloseableRegistry): AbstractKeyedStateBackend[K] = {
//
//  }
//
//  override def createOperatorStateBackend(env: Environment, operatorIdentifier: String, stateHandles: util.Collection[OperatorStateHandle], cancelStreamRegistry: CloseableRegistry): OperatorStateBackend = {
//
//  }
//}
