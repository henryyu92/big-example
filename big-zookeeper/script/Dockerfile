# ZooKeeper in Docker

FROM ubuntu:bionic
LABEL Author=henry

COPY *.sh /build/

RUN /build/prepare.sh && \
    cd /opt/zookeeper && /build/build.sh \
    cd / && /build/cleanup.sh && rm -rf /build

VOLUME /data

ADD ./zoo.cfg /opt/zookeeper/conf/zoo.cfg

ADD ./server /opt/server

EXPOSE 2181

CMD ["/opt/server"]
