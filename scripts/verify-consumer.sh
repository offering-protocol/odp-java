#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
repository=$(mktemp -d)
trap 'rm -rf "$repository"' EXIT INT TERM

version=$(
  "$root/mvnw" --batch-mode --no-transfer-progress \
    --file "$root/pom.xml" \
    help:evaluate \
    -Dexpression=project.version \
    -DforceStdout \
    -q
)

if [ "${ODP_CONSUMER_SOURCE:-local}" = "local" ]; then
  "$root/mvnw" --batch-mode --no-transfer-progress \
    --file "$root/pom.xml" \
    -DskipTests \
    package

  install_artifact() {
    file=$1
    pom=$2
    "$root/mvnw" --batch-mode --no-transfer-progress \
      org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
      -Dfile="$file" \
      -DpomFile="$pom" \
      -DlocalRepositoryPath="$repository"
  }

  install_artifact "$root/pom.xml" "$root/pom.xml"
  for artifact in odp-core odp-json-jackson2 odp-directory odp-agent odp-service; do
    install_artifact \
      "$root/$artifact/target/$artifact-$version.jar" \
      "$root/$artifact/pom.xml"
  done
fi

"$root/mvnw" --batch-mode --no-transfer-progress \
  --file "$root/testdata/consumer/pom.xml" \
  -Dmaven.repo.local="$repository" \
  -Dodp.version="$version" \
  verify
