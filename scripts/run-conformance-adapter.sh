#!/bin/sh
set -eu

exec ./mvnw --quiet --batch-mode --no-transfer-progress \
  -f tools/odp-conformance/pom.xml \
  -Dexec.mainClass=org.offeringprotocol.odp.conformance.ConformanceAdapter \
  -Dexec.classpathScope=runtime \
  org.codehaus.mojo:exec-maven-plugin:3.6.3:java
