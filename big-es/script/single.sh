#!/usr/bin/env sh

# 创建单个节点的 es 集群，包括 kabana
docker run \
--name 'es01' \
-e node.name=es01 \
-e cluster.name=docker-cluster \
-e bootstrap.memory_lock=true \
-e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
-v /data/elasticsearch:/usr/share/elasticsearch/data \
-p 9200:9200 -p 9300:9300 \
-ulimits memlock.soft=-1 memlock.hard=-1
-d \
docker.elastic.co/elasticsearch/elasticsearch:7.8.1

docker run \
--name 'kabana-7.8.1' \
-p 5601:5601 \
--link es-7.8.1:elasticsearch \
-d \
docker.elastic.co/kibana/kibana:7.8.1
