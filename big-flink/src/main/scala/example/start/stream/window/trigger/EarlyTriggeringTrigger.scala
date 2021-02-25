package example.start.stream.window.trigger

import org.apache.flink.api.common.functions.ReduceFunction
import org.apache.flink.api.common.state.ReducingStateDescriptor
import org.apache.flink.api.scala.typeutils.Types
import org.apache.flink.streaming.api.windowing.triggers.{Trigger, TriggerResult}
import org.apache.flink.streaming.api.windowing.windows.TimeWindow

/**
 * 每个 interval 时间间隔就触发窗口计算
 * @param interval
 */
class EarlyTriggeringTrigger(interval: Long) extends Trigger[Object, TimeWindow]{
  private type JLong = java.lang.Long
  // 当前时间戳最小值
  private val min = new ReduceFunction[JLong] {
    override def reduce(value1: JLong, value2: JLong): JLong = Math.min(value1, value2);
  }
  private val stateDesc = new ReducingStateDescriptor[JLong]("trigger-time", min, Types.LONG)

  // 处理接入的数据，每次数据接入都会调用
  override def onElement(element: Object, timestamp: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = {
    // Watermark 超过窗口结束时间则触发窗口计算并清理定时器
    if(window.maxTimestamp() <= ctx.getCurrentWatermark){
      clearTimerForState(ctx)
      TriggerResult.FIRE
    }else{
      // 注册窗口结束时间到 EventTime 定时器
      ctx.registerEventTimeTimer(window.maxTimestamp)
      // 获取当前分区状态中的时间戳
      val fireTimestamp = ctx.getPartitionedState(stateDesc)
      // 首次执行则计算下次触发计算的时间并注册到定时器
      if (fireTimestamp.get() == null){
        val start = timestamp - (timestamp % interval)
        val nextFireTimestamp = start + interval
        ctx.registerEventTimeTimer(nextFireTimestamp)
        fireTimestamp.add(nextFireTimestamp)
      }
      TriggerResult.CONTINUE
    }
  }

  override def onProcessingTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = TriggerResult.CONTINUE

  override def onEventTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = {
    if(time == window.maxTimestamp()){
      clearTimerForState(ctx)
      TriggerResult.FIRE
    }else{
      val fireTimestamp = ctx.getPartitionedState(stateDesc)
      if (fireTimestamp.get() == null){
        fireTimestamp.clear()
        fireTimestamp.add(time + interval)
        ctx.registerEventTimeTimer(time + interval)
        TriggerResult.FIRE
      }else{
        TriggerResult.CONTINUE
      }
    }
  }

  private def clearTimerForState(context: Trigger.TriggerContext)={
    val timestamp = context.getPartitionedState(stateDesc).get()
    if(timestamp != null){
      context.deleteEventTimeTimer(timestamp)
    }
  }


  override def canMerge: Boolean = true


  override def onMerge(window: TimeWindow, ctx: Trigger.OnMergeContext): TriggerResult = {
    ctx.mergePartitionedState(stateDesc)
    val nextFireTimestamp = ctx.getPartitionedState(stateDesc).get()
    if (nextFireTimestamp != null){
      ctx.registerEventTimeTimer(nextFireTimestamp)
    }
    TriggerResult.CONTINUE
  }

  override def clear(window: TimeWindow, ctx: Trigger.TriggerContext): Unit = {
    ctx.deleteEventTimeTimer(window.maxTimestamp())
    val fireTimestamp = ctx.getPartitionedState(stateDesc)
    val timestamp = fireTimestamp.get()
    if(timestamp != null){
      ctx.deleteEventTimeTimer(timestamp)
      fireTimestamp.clear()
    }
  }

  override def toString: String = s"ContinuousEventTimeTrigger($interval)"
}


object EarlyTriggeringTrigger{

}