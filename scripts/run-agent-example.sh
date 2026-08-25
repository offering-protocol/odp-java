#!/bin/sh
set -eu

./mvnw --quiet --batch-mode --no-transfer-progress -DskipTests install
exec ./mvnw --quiet --batch-mode --no-transfer-progress \
  -f examples/pom.xml \
  -Dexec.mainClass=org.offeringprotocol.odp.examples.AgentDiscovery \
  -Dexec.args="$*" \
  -Dexec.classpathScope=runtime \
  org.codehaus.mojo:exec-maven-plugin:3.6.3:java
