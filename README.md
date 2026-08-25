# Offering Discovery Protocol for Java

[![CI](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml/badge.svg)](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.offeringprotocol/odp-agent)](https://central.sonatype.com/namespace/org.offeringprotocol)
[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

Official Java software development kits for the
[Offering Discovery Protocol](https://www.offeringprotocol.org/), the open protocol for discovering
Services and navigating their Offerings.

ODP separates Service discovery from catalog discovery. An Agent searches the canonical directory
for candidate Services, inspects each Service's live ODP document, and then navigates or searches
that Service's Collections and Offerings.

## Modules

| Module                                        | Responsibility                                                        |
| --------------------------------------------- | --------------------------------------------------------------------- |
| [`odp-core`](./odp-core/README.md)            | Protocol models, validation, identity, references, and pagination     |
| [`odp-directory`](./odp-directory/README.md)  | Canonical production and sandbox directory client                     |
| [`odp-agent`](./odp-agent/README.md)          | Directory-to-Service discovery and Agent-oriented catalog workflows   |
| [`odp-service`](./odp-service/README.md)      | Service document, catalog operations, and integration helpers         |

Dependencies flow from role modules toward `odp-core`; `odp-agent` composes `odp-directory`.
`odp-core` does not depend on another ODP module, and `odp-service` does not depend on Agent or
directory behavior.

All artifacts use the Maven group `org.offeringprotocol` and require Java 17 or newer.

## Installation

Applications should depend on the module matching their role. Maven resolves its required ODP
modules transitively.

For an Agent application:

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-agent</artifactId>
  <version>0.1.0</version>
</dependency>
```

For a Service integration:

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-service</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle applications use the same coordinates:

```kotlin
implementation("org.offeringprotocol:odp-agent:0.1.0")
```

## Examples

Run the small Service and Agent examples in separate terminals:

```sh
./scripts/run-small-service.sh
./scripts/run-agent-example.sh
```

The Agent example explicitly uses a mock directory assembled from reachable Service origins. It
then performs live Service inspection, Offering listing, and full Offering retrieval. See
[examples/README.md](./examples/README.md) for the complete walkthrough.

## Development

The Maven Wrapper provides the complete merge gate:

```sh
./mvnw verify
```

Verify the published module boundaries from an isolated consumer project with:

```sh
./scripts/verify-consumer.sh
```

Format Java sources with:

```sh
./mvnw spotless:apply
```

Generate Agent and Service conformance reports with:

```sh
ODP_SPECS_DIR=/path/to/odp-specs ./scripts/run-conformance.sh
```

Run the Java Agent against the Node.js reference Service with:

```sh
ODP_NODE_DIR=/path/to/odp-node ./scripts/run-node-interoperability.sh
```

The shared harness writes release evidence to `.conformance/reports/`.

See [DEVELOPMENT.md](./DEVELOPMENT.md) for repository conventions and
[`odp-specs`](https://github.com/offering-protocol/odp-specs) for the normative draft, schemas,
examples, and test vectors.

## Security

See [SECURITY.md](./SECURITY.md) for vulnerability reporting.

## License

MIT.
