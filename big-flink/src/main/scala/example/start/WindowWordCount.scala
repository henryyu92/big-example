package example.start

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time

object WindowWordCount {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // Source
    val text = env.socketTextStream("localhost", 9999)

    // Transform
    val count = text.flatMap(_.toLowerCase.split("\\w+"))
      .filter(_.nonEmpty)
      .map((_, 1))
      .keyBy(_._1)
      // Window
      .window(TumblingProcessingTimeWindows.of(Time.seconds(5)))
      .sum(1)

    // Sink
    count.print()

    env.execute("Window Stream Word Count")

  }

}
