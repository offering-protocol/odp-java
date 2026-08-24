# Offering Discovery Protocol for Java

[![CI](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml/badge.svg)](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml)
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

## Development

The Maven Wrapper provides the complete merge gate:

```sh
./mvnw verify
```

Format Java sources with:

```sh
./mvnw spotless:apply
```

See [DEVELOPMENT.md](./DEVELOPMENT.md) for repository conventions and
[`odp-specs`](https://github.com/offering-protocol/odp-specs) for the normative draft, schemas,
examples, and test vectors.

## Security

See [SECURITY.md](./SECURITY.md) for vulnerability reporting.

## License

MIT.
