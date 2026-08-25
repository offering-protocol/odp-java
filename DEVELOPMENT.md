# Development

## Requirements

- Java 17 or newer.
- No system Maven installation; use the checked-in Maven Wrapper.

## Verification

Run the complete repository gate before merging:

```sh
./mvnw verify
```

The gate compiles all modules, runs tests, enforces formatting and dependency convergence, and runs
Checkstyle, PMD, SpotBugs, and JaCoCo reporting.

Format Java sources with:

```sh
./mvnw spotless:apply
```

Run the shared protocol conformance harness with:

```sh
ODP_SPECS_DIR=/path/to/odp-specs ./scripts/run-conformance.sh
```

Run cross-language interoperability against the Node.js reference Service with:

```sh
ODP_NODE_DIR=/path/to/odp-node ./scripts/run-node-interoperability.sh
```

## Module boundaries

`odp-core` owns transport-independent protocol behavior. `odp-directory`, `odp-agent`, and
`odp-service` are role modules. `odp-agent` can compose `odp-directory`; the other role modules do
not depend on one another.

The normative protocol is maintained in `offering-protocol/odp-specs`. Confirm schema, wire, and
conformance behavior there before implementing or changing it in Java.

## Releases

The Maven reactor produces independently publishable artifacts under `org.offeringprotocol`.
Release publication requires sources, Javadocs, signatures, provenance, shared conformance, and
clean external-consumer verification.
