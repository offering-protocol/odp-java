#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
repository=$(mktemp -d)
gradle_home=$(mktemp -d)
trap 'rm -rf "$repository" "$gradle_home"' EXIT INT TERM

version=$(
  "$root/mvnw" --batch-mode --no-transfer-progress \
    --file "$root/pom.xml" \
    help:evaluate \
    -Dexpression=project.version \
    -DforceStdout \
    -q
)

case "${ODP_CONSUMER_SOURCE:-local}" in
  local)
    "$root/scripts/install-consumer-artifacts.sh" "$repository" "$version"
    ;;
  central) ;;
  *)
    echo "ODP_CONSUMER_SOURCE must be local or central." >&2
    exit 2
    ;;
esac

if [ "${ODP_CONSUMER_SOURCE:-local}" = "local" ]; then
  GRADLE_USER_HOME="$gradle_home" gradle \
    --no-daemon \
    --console=plain \
    --project-dir "$root/testdata/consumer-gradle" \
    -PodpVersion="$version" \
    -PodpRepository="$repository" \
    verifyConsumer
else
  GRADLE_USER_HOME="$gradle_home" gradle \
    --no-daemon \
    --console=plain \
    --project-dir "$root/testdata/consumer-gradle" \
    -PodpVersion="$version" \
    verifyConsumer
fi
