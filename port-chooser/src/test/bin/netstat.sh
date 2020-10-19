#!/bin/sh
#
# Copyright 2020 Terracotta, Inc., a Software AG company.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Run NetStat tool

PROJECT_DIR=`dirname "${0}"`
PROJECT_DIR=`eval "cd \"${PROJECT_DIR}/../../../..\" && pwd"`

maven="${MAVEN_SETTINGS:-${HOME}/.m2}"
if test ! -d "${maven}"; then
  echo "Directory \"${maven}\" does not exist; set the MAVEN_SETTINGS environment variable to point to the Maven .m2 directory" >&2
  exit 1
fi

CLASSPATH=
for p in \
   "${PROJECT_DIR}/port-chooser/target/classes" \
   "${PROJECT_DIR}/port-chooser/target/test-classes" \
   "${PROJECT_DIR}/tools/target/classes" \
   "${maven}/repository/org/slf4j/slf4j-api/1.7.25/slf4j-api-1.7.25.jar" \
   "${maven}/repository/ch/qos/logback/logback-classic/1.2.3/logback-classic-1.2.3.jar" \
   "${maven}/repository/ch/qos/logback/logback-core/1.2.3/logback-core-1.2.3.jar"
do
  CLASSPATH="${CLASSPATH:+${CLASSPATH}:}${p}"
done
export CLASSPATH

java org.terracotta.utilities.test.net.NetStat
