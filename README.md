# Offering Discovery Protocol for Java

[![CI](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml/badge.svg)](https://github.com/offering-protocol/odp-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.offeringprotocol/odp-agent)](https://central.sonatype.com/namespace/org.offeringprotocol)
[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

Official Java software development kit for the
[Offering Discovery Protocol](https://www.offeringprotocol.org/), the open protocol for discovering
Services and navigating their Offerings.

ODP separates Service discovery from catalog discovery. An Agent searches the canonical directory
for candidate Services, inspects each Service's live ODP document, and then navigates or searches
that Service's Collections and Offerings. Full Offering details can describe structured attributes,
price previews, images, and executable Actions without forcing every industry into one catalog
schema.

## Start here

Choose the module that matches the role your application implements:

| I am building...                             | Start with                                   | Responsibility                                                      |
| -------------------------------------------- | -------------------------------------------- | ------------------------------------------------------------------- |
| An Agent, command-line tool, or automation   | [`odp-agent`](./odp-agent/README.md)         | Directory-to-Service discovery and live catalog navigation          |
| An ODP Service                              | [`odp-service`](./odp-service/README.md)     | Service document, fixed routes, static or storage-backed operations |
| A directory-only integration                | [`odp-directory`](./odp-directory/README.md) | Canonical production or sandbox Service search                      |
| An ODP validator or protocol implementation | [`odp-core`](./odp-core/README.md)           | Models, bundled schemas, identity, references, and pagination       |

All artifacts use Maven group `org.offeringprotocol`, require Java 17 or newer, and are available
from Maven Central without adding a repository.

Dependencies flow from role modules toward `odp-core`; `odp-agent` composes `odp-directory`.
`odp-core` does not depend on another ODP module, and `odp-service` does not depend on Agent or
directory behavior.

## Installation

For an Agent application:

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-agent</artifactId>
  <version>0.1.1</version>
</dependency>
```

For a Service integration:

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-service</artifactId>
  <version>0.1.1</version>
</dependency>
```

Gradle uses the same coordinates:

```kotlin
implementation("org.offeringprotocol:odp-agent:0.1.1")
```

Maven resolves the required Core and Directory modules transitively. Applications should not add
every ODP module to one project unless they actually implement multiple roles.

## Agent quick start

`OdpAgent` performs two-stage discovery: it searches the canonical directory and then searches the
live catalogs of matching Services. A Service failure becomes an `IssueEvent` without discarding
Offerings returned by other Services.

```java
import org.offeringprotocol.odp.agent.OdpAgent;
import org.offeringprotocol.odp.directory.DirectoryClient;

DirectoryClient directory = DirectoryClient.create();
OdpAgent agent = new OdpAgent(directory);

for (OdpAgent.DiscoveryEvent event : agent.searchOfferings("plants", 10, 10)) {
    if (event instanceof OdpAgent.OfferingEvent offering) {
        System.out.printf("%s: %s%n", offering.service().name(), offering.offering().name());
    } else if (event instanceof OdpAgent.IssueEvent issue) {
        System.err.printf("%s: %s%n", issue.service().serviceOrigin(), issue.message());
    }
}
```

For a known Service, `OdpServiceClient` inspects `/.well-known/odp`, exposes the advertised
operations, and provides Collection and Offering list, search, get, and continuation methods. See
the [Agent integration guide](./odp-agent/README.md) for direct navigation, sandbox selection,
localization, pagination, and authenticated transport composition.

## Service quick start

The minimum Service integration publishes `/.well-known/odp`, lists Offerings, and retrieves one
Offering. `StaticCatalog` provides those operations for a small in-memory catalog.

```java
import java.util.List;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.service.OdpService;
import org.offeringprotocol.odp.service.StaticCatalog;

Offering offering = OdpJson.parseOffering("""
        {
          "odp_version": "1.0",
          "id": "rubber-plant",
          "name": "Rubber Plant",
          "description": "A resilient indoor plant."
        }
        """);

OdpService service = OdpService.builder(
                "Example Plant Store",
                "Indoor plants selected for homes and offices.",
                "en",
                "/odp")
        .keywords(List.of("plants", "indoor-plants"))
        .endpoints(StaticCatalog.create(List.of(offering), List.of()))
        .build();
```

Adapt the framework's incoming request to `OdpHttpRequest`, pass it to `service.handle(...)`, and
write the returned `OdpHttpResponse`. Large catalogs provide handlers backed by their own storage
and indexes instead of materializing the catalog in memory. See the
[Service integration guide](./odp-service/README.md) and the
[runnable small Service](./examples/README.md#small-service).

## Protocol composition

ODP advertises AEP enrollment, operation authentication requirements, MPP and x402 payment
support, and Offering Actions. It does not duplicate those protocols' credential or payment
semantics.

The default Java Agent transport performs anonymous HTTP requests. Applications inject an
`OdpTransport` when catalog requests need AEP credentials, MPP, x402, or application-specific
network policy. The Service runtime advertises authentication requirements but expects the hosting
application to enforce authentication and payment before or around the ODP handler.

An Offering may describe an Action, but the Java SDK never invokes an Action implicitly. The
application selects the Action and remains responsible for user approval, authentication, payment,
and state-changing requests.

## Runtime boundaries

Applications own persistent caching, authentication context, authorization, catalog persistence,
indexing, rate limiting, and Action execution. The clients enforce ODP document validation,
same-origin redirect and continuation rules, response-size limits, and fixed production or sandbox
directory selection.

`OdpServiceClient` fetches and validates its Service Document when the client is created and retains
that inspection for the client's lifetime. The Java SDK does not maintain a persistent cache or
refresh a live client automatically; applications choose when to reuse or recreate clients.

## Runnable examples

Run the small Service and Agent examples in separate terminals:

```sh
./scripts/run-small-service.sh
./scripts/run-agent-example.sh
```

The Agent example explicitly uses a mock directory assembled from reachable Service origins. It
then performs live Service inspection, Offering listing, and full Offering retrieval. See
[examples/README.md](./examples/README.md) for the walkthrough and source map.

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

See [SECURITY.md](./SECURITY.md) for vulnerability reporting. The examples use illustrative
in-memory catalogs and an explicitly labeled mock directory.

## License

MIT.
