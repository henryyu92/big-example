
# 基础镜像
FROM openjdk:8
# 维护信息
MAINTAINER: test
# 配置指令
WORKDIR /usr/src/hbase
curl http://apache.forsale.plus/hbase/2.3.1/hbase-2.3.1-bin.tar.gz
ADD ./Hello.java .
# 操作指令
RUN javac Hello.java
CMD ["java", "Hello"]
