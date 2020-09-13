#!/bin/sh

. /build/config.sh

apt-get update -y

apt-get install $minimal_apt_get_args $ZOO_BUILD_PACKAGES

cd /opt

curl -SL $ZOO_DIST/zookeeper-$ZOO_VERSION/apache-zookeeper-$ZOO_VERSION-bin.tar.gz | tar -x -z && mv apache-zookeeper-$ZOO_VERSION-bin zookeeper