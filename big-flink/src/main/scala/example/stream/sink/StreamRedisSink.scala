package example.stream.sink

import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.apache.flink.streaming.connectors.redis.RedisSink
import org.apache.flink.streaming.connectors.redis.common.mapper.{RedisCommand, RedisCommandDescription, RedisMapper}
import org.apache.flink.streaming.connectors.redis.common.config.{FlinkJedisClusterConfig, FlinkJedisPoolConfig, FlinkJedisSentinelConfig}


object StreamRedisSink {

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

  }

  def redisSink[T](stream: DataStream[T]): Unit = {
    stream.addSink(new RedisSink(redisConfig(), new RedisExampleMapper[T]()))
  }

  def redisConfig(): FlinkJedisPoolConfig = {
    new FlinkJedisPoolConfig.Builder().setHost("127.0.0.1").build
  }

  def redisClusterConfig(): FlinkJedisPoolConfig = {
    new FlinkJedisClusterConfig.Builder().build()
  }

  def redisSentinelConfig(): FlinkJedisPoolConfig = {
    new FlinkJedisSentinelConfig.Builder().build()
  }
}

class RedisExampleMapper[T] extends RedisMapper[T] {
  override def getCommandDescription: RedisCommandDescription = {
    new RedisCommandDescription(RedisCommand.HSET, "HASH_NAME")
  }

  override def getKeyFromData(data: T): String = {
    data.toString
  }

  override def getValueFromData(data: T): String = {
    data.toString
  }
}
