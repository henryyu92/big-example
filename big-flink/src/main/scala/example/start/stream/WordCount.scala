package example.start.stream

import org.apache.flink.api.common.serialization.SimpleStringEncoder
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.core.fs.Path
import org.apache.flink.streaming.api.functions.sink.filesystem.StreamingFileSink
import org.apache.flink.streaming.api.scala._


object WordCount {

  def main(args: Array[String]): Unit = {

    val hostname = if (args.length > 2) args(1) else "localhost"
    val port = if (args.length > 3) args(2).toInt else 7777
    val out = if (args.length > 3) args(3) else ""

    // --host localhost --port 7777
    val parameterTool = ParameterTool.fromArgs(args)

    // 执行环境
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // 数据源(source)
    val stream = env.socketTextStream(hostname, port)

    // transform
    val transformedStream = stream.flatMap(_.split(" "))
      .map((_, 1))
      .keyBy(0)
      .sum(1)

    // 输出(sink)
    if(!out.isEmpty){
      val outSink = StreamingFileSink.forRowFormat(new Path(out), new SimpleStringEncoder[(String, Int)]).build()
      transformedStream.addSink(outSink)
    }else{
      transformedStream.print
    }

    // 提交 job
    env.execute("stream word count")

  }
}
