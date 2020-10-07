package example.start.stream.source

import java.util.concurrent.TimeUnit

import example.start.stream.SensorReading
import org.apache.flink.streaming.api.functions.source.SourceFunction


class CustomSource extends SourceFunction[SensorReading]{

  def CustomSource(interval: Long){
    this.interval = interval
  }

  @volatile var stop: Boolean = false

  var interval: Long = 1000 * 3L

  override def run(ctx: SourceFunction.SourceContext[SensorReading]): Unit = {
    while (!stop){

      // 生成数据
      val sensor = SensorReading("", 1L, 1.1)

      ctx.collect(sensor)
      TimeUnit.MILLISECONDS.sleep(interval)
    }
  }

  override def cancel(): Unit = {
    stop = true
  }
}
