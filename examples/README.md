# Runnable examples

The examples demonstrate the two ODP integration roles with the same public Java modules published
to Maven Central. Run all commands from the repository root with Java 17 or newer.

## Source map

| Source                                                                                         | Purpose                                                          |
| ---------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| [`SmallService.java`](./src/main/java/org/offeringprotocol/odp/examples/SmallService.java)      | Framework adapter, Service builder, static catalog, and search   |
| [`AgentDiscovery.java`](./src/main/java/org/offeringprotocol/odp/examples/AgentDiscovery.java)  | Inspection, terse listing, and full Offering retrieval           |
| [`MockDirectory.java`](./src/main/java/org/offeringprotocol/odp/examples/MockDirectory.java)    | Clearly isolated local stand-in for candidate Service discovery  |

## Small Service

The small Service keeps one Collection and two Offerings in memory. It publishes a Service
Document, provides the required Offering list and detail operations, adds Offering search, and
exposes a free download Action.

```sh
./scripts/run-small-service.sh
```

The Service listens on `http://127.0.0.1:4103` by default. Set `PORT` to use another port:

```sh
PORT=4203 ./scripts/run-small-service.sh
```

At startup it prints the URLs an integrator needs:

```text
Small ODP Service listening at http://127.0.0.1:4103
Service Document: http://127.0.0.1:4103/.well-known/odp
Offerings: http://127.0.0.1:4103/odp/offerings
```

Every request prints its method, path, and response status. Stop the process with Control-C.

The example uses the JDK `HttpServer` only as a transparent adapter. A Spring, Jakarta, Netty, or
other integration performs the same conversion into `OdpHttpRequest` and writes the returned
`OdpHttpResponse` through its own response API.

## Agent discovery

The Agent example composes a mock directory from reachable local Services, prints every inspected
Service Document, lists terse Offerings, and retrieves each full Offering.

Start the small Service in another terminal, then run:

```sh
./scripts/run-agent-example.sh
```

Without arguments, the mock directory checks ports 4101, 4102, and 4103 and includes only reachable
ODP Services. Pass one or more Service origins to use an explicit set:

```sh
./scripts/run-agent-example.sh https://service.example
```

The mock directory is example infrastructure. It does not call or imitate the canonical directory
API. Production Agent applications use `DirectoryClient.create()` or construct `OdpAgent` with a
production or sandbox Directory client.

The output separates each discovery stage:

```text
Mock directory contains 1 reachable ODP Service(s).

ODP Service Document:
{...}

Terse Offering list:
{...}

Full Offering agent-guide:
{...}
```

## From example to application

For an Agent integration:

1. Replace `MockDirectory` with the canonical `DirectoryClient`.
2. Choose production or sandbox explicitly when constructing the directory client.
3. Use `OdpServiceClient` for each selected Service and check its advertised operations.
4. Supply an `OdpTransport` when ODP requests require AEP, MPP, x402, or application credentials.
5. Apply application-specific caching, result ceilings, retries, and observability.

For a Service integration:

1. Map the application's catalog data into ODP `Offering` and optional `Collection` records.
2. Use `StaticCatalog` only when the complete catalog is appropriate for an immutable memory
   snapshot; otherwise provide storage-backed handlers.
3. Mount `OdpService` at the well-known path and configured endpoint base.
4. Enforce authentication, payment, authorization, and rate limits in the hosting HTTP stack.
5. Publish appropriate cache metadata and operational telemetry through the host application.

See the [Agent guide](../odp-agent/README.md) and
[Service guide](../odp-service/README.md) for the complete public API boundaries.
