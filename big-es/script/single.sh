#!/usr/bin/env sh

# 创建单个节点的 es 集群，包括 kabana
docker run \
--name 'es-7.8.1' \
-e "discovery.type=single-node" \
-p 9200:9200 -p 9300:9300 \
-d \
docker.elastic.co/elasticsearch/elasticsearch:7.8.1

docker run \
--name 'kabana-7.8.1' \
-p 5601:5601 \
--link es-7.8.1:elasticsearch \
-d \
docker.elastic.co/kibana/kibana:7.8.1
