#!/usr/bin/env bash
#
# Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0 which
# accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#
zookeeper_version="%%zookeeper.version.recommended%%"

# kafka 0.9 versions
kafka_version="%%kafka.version%%"
zkclient_version="%%zkclient.version%%"
jopt_version="%%kafka.jopt.version%%"

# kafka 0.10 versions
# kafka_version="0.10.2.1"
# zkclient_version="0.10"
# jopt_version="4.9"

# kafka 1.0 versions
# kafka_version="1.0.0"
# zkclient_version="0.10"
# jopt_version="5.0.4"

# Load common functions and setup
if [ -z "${%%gmtools.dist.name%%_HOME}" ]; then
  export %%gmtools.dist.name%%_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi
. $%%gmtools.dist.name%%_HOME/bin/common-functions.sh

install_dir="${1:-${%%gmtools.dist.name%%_HOME}/lib}"

# Resource download location
base_url="${GEOMESA_MAVEN_URL:-https://search.maven.org/remotecontent?filepath=}"

declare -a urls=(
  "${base_url}org/apache/kafka/kafka_2.11/$kafka_version/kafka_2.11-$kafka_version.jar"
  "${base_url}org/apache/kafka/kafka-clients/$kafka_version/kafka-clients-$kafka_version.jar"
  "${base_url}org/apache/zookeeper/zookeeper/$zookeeper_version/zookeeper-$zookeeper_version.jar"
  "${base_url}com/101tec/zkclient/$zkclient_version/zkclient-$zkclient_version.jar"
  "${base_url}net/sf/jopt-simple/jopt-simple/$jopt_version/jopt-simple-$jopt_version.jar"
  "${base_url}com/yammer/metrics/metrics-core/2.2.0/metrics-core-2.2.0.jar"
)

downloadUrls "$install_dir" urls[@]
