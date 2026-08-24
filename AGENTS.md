# AGENTS.md

## Repository

This repository contains the official Java modules for ODP:

- `odp-core`: transport-independent protocol primitives.
- `odp-directory`: canonical directory client.
- `odp-agent`: Agent-side composition.
- `odp-service`: Service-side integration.

The normative protocol is maintained in `offering-protocol/odp-specs`. Check that source before
implementing or changing wire behavior.

## Verification

Run `./mvnw verify` before merging. Public APIs must be backed by tests and authoritative protocol
behavior.

## Conventions

- Support Java 17 and newer; continuous integration covers Java 17, 21, and 25.
- Use the Java standard library when it is sufficient and justify every additional dependency.
- Return typed failures rather than logging from library code.
- Keep dependency direction aligned with the module responsibilities above.
- Keep public APIs small, idiomatic, immutable where practical, and backed by tests.
- Describe current behavior; do not leave speculative or historical comments.
- Do not introduce framework dependencies into `odp-core`.
