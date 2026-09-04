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

for consumer in consumer consumer-direct; do
  "$root/mvnw" --batch-mode --no-transfer-progress \
    --file "$root/testdata/$consumer/pom.xml" \
    -Dmaven.repo.local="$repository" \
    -Dodp.version="$version" \
    verify
done
