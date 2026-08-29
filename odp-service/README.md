# ODP Service

Framework-neutral Service document, fixed-route catalog operations, request handling, and Problem
Details for ODP Services.

Small Services can expose an immutable in-memory catalog. Large Services provide handlers backed by
their existing storage and indexes. The runtime invokes one configured operation for each request;
it does not load, copy, sort, or index a storage-backed catalog.

## Install

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-service</artifactId>
  <version>0.2.0</version>
</dependency>
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-json-jackson2</artifactId>
  <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("org.offeringprotocol:odp-service:0.2.0")
implementation("org.offeringprotocol:odp-json-jackson2:0.2.0")
```

The Service module brings in `odp-core` transitively and does not depend on Agent or directory
behavior. Replace `odp-json-jackson2` with `odp-json-jackson3` in a Jackson 3 application. Exactly
one provider must be present at runtime; no programmatic configuration is required.

## Minimum integration

Every ODP Service must list Offerings and retrieve one Offering. `StaticCatalog` supplies those
required handlers from a small in-memory catalog.

```java
Offering offering = OdpJson.parseOffering("""
        {
          "odp_version": "1.0",
          "id": "rubber-plant",
          "name": "Rubber Plant",
          "description": "A resilient indoor plant."
        }
        """);

Map<OdpOperation, OdpService.Endpoint> endpoints =
        StaticCatalog.create(List.of(offering), List.of());

OdpService service = OdpService.builder(
                "Example Plant Store",
                "Indoor plants selected for homes and offices.",
                "en",
                "/odp")
        .keywords(List.of("plants", "indoor-plants"))
        .websiteUrl("https://store.example")
        .endpoints(endpoints)
        .build();
```

The builder sets `odp_version` from this SDK and derives the advertised operations from the
configured endpoints. The default localization list contains the selected language. Optional
builder methods configure additional localizations, branding, MCP endpoints, enrollment and payment
protocols, payment origins, search capabilities, OpenAPI, documentation, support, status, and
website metadata.

Construction validates the final Service Document. Building without `list-offerings` and
`get-offering` handlers fails immediately.

## Small catalogs

Adding Collections enables Collection listing, retrieval, and direct Offering membership:

```java
Map<OdpOperation, OdpService.Endpoint> endpoints =
        StaticCatalog.create(offerings, collections);
```

The static catalog:

- takes immutable snapshots of the supplied lists;
- verifies unique Offering and Collection identifiers;
- returns terse or full representations;
- defaults page size to 50 and accepts limits through 100;
- uses opaque, integrity-protected stateless continuations that expire after one hour; and
- advertises only the operations supplied by its resources.

The simple overload generates a new continuation signing key when the catalog is created. For
continuations that must survive a process restart or work across multiple instances, supply the
same secret key of at least 32 bytes to each instance:

```java
Map<OdpOperation, OdpService.Endpoint> endpoints =
        StaticCatalog.create(offerings, collections, continuationKey);
```

Store that key as an application secret. Rotating it intentionally invalidates outstanding
continuations.

`StaticCatalog` does not implement search. Add a search endpoint when the application has an index
or another deterministic search implementation:

```java
Map<OdpOperation, OdpService.Endpoint> endpoints =
        new EnumMap<>(StaticCatalog.create(offerings, collections));

endpoints.put(
        OdpOperation.SEARCH_OFFERINGS,
        new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
            SearchRequests.Offerings search =
                    OdpJson.parseOfferingSearchRequest(request.body());
            return catalogRepository.searchOfferings(search);
        }));
```

The handler returns an ODP-compatible page model. Initial search arrives through POST with the
validated representation, language, and body available on `CatalogRequest`. A continuation is a GET
chosen and interpreted by the Service implementation.

## Storage-backed operations

Start with an `EnumMap<OdpOperation, OdpService.Endpoint>` and configure only operations the Service
actually supports. `CatalogRequest` exposes:

| Value            | Meaning                                                       |
| ---------------- | ------------------------------------------------------------- |
| `identifier`     | Offering or Collection identifier for detail/member routes   |
| `representation` | Normalized `terse` or `full` representation                   |
| `limit`          | Optional validated limit from 1 through 100                  |
| `cursor`         | Opaque cursor query value when the Service uses one           |
| `language`       | First `Accept-Language` header value                          |
| `body`           | Search body for an initial POST                               |
| `request`        | Original normalized ODP request                              |

Return `null` when a requested resource does not exist. Throw `OdpServiceException` with a status,
stable code, and safe message for an intentional ODP Problem Details response. Unexpected handler
exceptions remain visible to the hosting application rather than being mislabeled by the SDK.

## HTTP framework adapter

Mount the same `OdpService` handler so it receives both `/.well-known/odp` and the configured
endpoint base. Adapt the hosting framework's request and response at the boundary:

```java
OdpHttpRequest request = new OdpHttpRequest(
        method,
        path,
        queryParameters,
        requestHeaders,
        requestBody);

OdpHttpResponse response = service.handle(request);

setStatus(response.status());
response.headers().forEach(this::setHeader);
writeBody(response.body());
```

`queryParameters` and `requestHeaders` are maps from a String to all supplied values. The complete
standard-library HTTP adapter is in
[`SmallService.java`](../examples/src/main/java/org/offeringprotocol/odp/examples/SmallService.java).

The runtime owns fixed operation routes, representation and limit validation, the 65,536-byte
request-body ceiling, Service Document generation, media types, and ODP Problem Details. The host
application owns connection policy, HTTP caching headers, compression, observability, rate limits,
and deployment lifecycle.

Service Document protocol advertisements are validated against the declared ODP version and accept
only the enrollment, payment, and trust protocol names defined by that version.

## Authentication and payment

Operation authentication defaults to `not-required`. Override advertised requirements without
rewriting a catalog handler:

```java
OdpService service = OdpService.builder(name, description, "en", "/odp")
        .endpoints(endpoints)
        .protocols(new ServiceDocument.Protocols(
                List.of(new ServiceDocument.EnrollmentProtocol("aep")), null))
        .operationAuthentication(Map.of(
                OdpOperation.GET_OFFERING,
                AuthenticationRequirement.REQUIRED))
        .build();
```

Every overridden operation must have a configured endpoint. The requirement changes the Service
Document advertisement; it does not authenticate the caller. An `optional` or `required` operation
also requires the Service Document to advertise an enrollment protocol, as shown above. Enforce API
keys, AEP credentials, MPP, x402, authorization, and application policy in HTTP middleware before
invoking `handle(...)`. A live authentication or payment challenge remains authoritative.

## Concurrency and lifecycle

`OdpService` copies its endpoint map and generated Service Document at construction. `StaticCatalog`
copies the supplied catalog lists and its continuation key. These objects can be shared across
request threads when custom handlers and their dependencies are themselves thread-safe.

Rebuild the Service when its advertised document or operation set changes. Storage-backed handlers
can return current catalog data without rebuilding the Service.

## Related documentation

- [Core models and validation](../odp-core/README.md)
- [Runnable Service example](../examples/README.md#small-service)
- [Normative ODP specifications](https://www.offeringprotocol.org/)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-service)
