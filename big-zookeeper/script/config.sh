# This file intended to be sourced

# . /build/config.sh

ZOO_VERSION=3.6.2
ZOO_DIST="http://archive.apache.org/dist/zookeeper"

export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

minimal_apt_get_args='-y --no-install-recommends'

## Build time dependencies ##

ZOO_BUILD_PACKAGES="curl"

## Run time dependencies ##
HBASE_RUN_PACKAGES="openjdk-8-jre-headless"
