#!/bin/sh
set -eu

specs_dir=${ODP_SPECS_DIR:-../odp-specs}
output_dir=${ODP_CONFORMANCE_OUTPUT:-.conformance/reports}
implementation_version=${ODP_JAVA_VERSION:-0.1.0}
implementation_version=${implementation_version#v}

./mvnw --quiet --batch-mode --no-transfer-progress -DskipTests install
mkdir -p "$output_dir"

for role in agent service; do
  ruby "$specs_dir/ietf/scripts/run_conformance.rb" \
    --role "$role" \
    --implementation-name odp-java \
    --implementation-version "$implementation_version" \
    --output "$output_dir/$role.json" \
    -- ./scripts/run-conformance-adapter.sh
done
