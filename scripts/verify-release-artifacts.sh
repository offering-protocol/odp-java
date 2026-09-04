#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
settings=$(mktemp)
effective_bom=$(mktemp)
trap 'rm -f "$settings" "$effective_bom"' EXIT INT TERM
printf '%s\n' \
  '<settings>' \
  '  <servers>' \
  '    <server>' \
  '      <id>central</id>' \
  '      <username>dry-run</username>' \
  '      <password>dry-run</password>' \
  '    </server>' \
  '  </servers>' \
  '</settings>' \
  > "$settings"
version=$(
  "$root/mvnw" --batch-mode --no-transfer-progress \
    --file "$root/pom.xml" \
    help:evaluate \
    -Dexpression=project.version \
    -DforceStdout \
    -q
)

case "$version" in
  *-SNAPSHOT)
    echo "Release verification requires a stable project version." >&2
    exit 1
    ;;
esac

"$root/mvnw" --batch-mode --no-transfer-progress \
  --file "$root/pom.xml" \
  --settings "$settings" \
  -Prelease \
  -Dgpg.skip=true \
  -DskipPublishing=true \
  clean deploy

for artifact in odp-core odp-json-jackson2 odp-json-jackson3 odp-directory odp-agent odp-service; do
  for classifier in "" -sources -javadoc; do
    file="$root/$artifact/target/$artifact-$version$classifier.jar"
    if [ ! -s "$file" ]; then
      echo "Missing release artifact: $file" >&2
      exit 1
    fi
  done
done

"$root/mvnw" --batch-mode --no-transfer-progress \
  --file "$root/odp-bom/pom.xml" \
  help:effective-pom \
  -Doutput="$effective_bom"

if grep -Eq '<artifactId>(junit-bom|jackson-bom)</artifactId>' "$effective_bom"; then
  echo "odp-bom must not manage JUnit or Jackson." >&2
  exit 1
fi

for artifact in odp-core odp-json-jackson2 odp-json-jackson3 odp-directory odp-agent odp-service; do
  if ! grep -q "<artifactId>$artifact</artifactId>" "$effective_bom"; then
    echo "odp-bom does not manage $artifact." >&2
    exit 1
  fi
done

managed_dependencies=$(sed -n '/<dependencyManagement>/,/<\/dependencyManagement>/p' "$root/odp-bom/pom.xml")
for artifact in odp-java odp-examples odp-conformance; do
  if printf '%s\n' "$managed_dependencies" | grep -q "<artifactId>$artifact</artifactId>"; then
    echo "odp-bom must not manage $artifact." >&2
    exit 1
  fi
done
