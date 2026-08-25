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

All publishable modules share the parent reactor's stable semantic version. A release publishes the
`odp-java` parent and the `odp-core`, `odp-directory`, `odp-agent`, and `odp-service` artifacts to
Maven Central. Examples and conformance tooling remain repository-only modules.

The Release workflow runs manually from `main`. It requires the `org.offeringprotocol` Maven Central
namespace and these repository secrets:

- `CENTRAL_USERNAME`: Central Portal user-token username.
- `CENTRAL_PASSWORD`: Central Portal user-token password.
- `MAVEN_GPG_PRIVATE_KEY`: ASCII-armored private signing key.
- `MAVEN_GPG_PASSPHRASE`: signing-key passphrase.

Before publication, the workflow requires a stable project version, the complete Maven gate, shared
Agent and Service conformance, Node.js interoperability, and isolated consumer compilation. It then
creates the matching `v<version>` tag, publishes signed binary, source, Javadoc, and POM artifacts,
attests the Maven artifacts, verifies Maven Central consumption, and creates the GitHub release with
conformance evidence.
