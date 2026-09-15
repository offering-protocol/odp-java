# ODP Service

Framework-neutral Service document, fixed-route catalog operations, request handling, and Problem
Details for ODP Services.

Small Services can expose an immutable in-memory catalog. Large Services provide handlers backed by
their existing storage and indexes. The runtime invokes one configured operation for each request;
it does not load, copy, sort, or index a storage-backed catalog.

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-service` and
exactly one JSON provider.

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
- uses opaque, HMAC-protected stateless continuations bound to the path, representation, and page
  size they were issued for, valid for at least the hour PAG-19 requires and expiring on an hour
  boundary so a cursor does not record the moment it was handed out; and
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
| `language`       | Language selected by RFC 4647 Lookup against `localizations`  |
| `body`           | Search body for an initial POST                               |
| `request`        | Original normalized ODP request                              |

Return `null` when a requested resource does not exist. Throw `OdpServiceException` with a status,
stable code, and safe message for an intentional ODP Problem Details response; that message reaches
the caller as the problem's `detail`, so it describes the request rather than the Service's internal
state. Any other exception a handler throws becomes a `500` `INTERNAL_ERROR` whose detail says only
that the request could not be processed: an exception message can name a query, a table, or a host
an Agent is not permitted to learn. Log the original where the Service logs, not in the response.

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
request-body ceiling, Service Document generation, media types, content negotiation, language
selection, validators and conditional retrieval, freshness, and ODP Problem Details. The host
application owns connection policy, compression, observability, rate limits, and deployment
lifecycle. Relay every header the response carries; several of them are protocol requirements.

### What the runtime answers on its own

| Condition | Response |
| --- | --- |
| `Accept` excludes `application/odp+json` (MED-04) | `406` `NOT_ACCEPTABLE` |
| A request body whose media type is not `application/odp+json` (MED-06) | `415` `UNSUPPORTED_MEDIA_TYPE` |
| A published path reached by another method | `405` `METHOD_NOT_ALLOWED` with `Allow` |
| `HEAD` of any resource | The fields of the `GET`, with `Content-Length` and no body |
| `If-None-Match` naming the current validator (PAG-31) | `304` `Not Modified` |
| `If-Match` naming another validator | `412` `PRECONDITION_FAILED` |
| A repeated or unsupported `representation`, `limit`, or `cursor` (SVC-73) | `400` `INVALID_REQUEST` |
| A path segment that is not a Local Resource Identifier (IDN-08) | `404` `NOT_FOUND` |
| A request body past 65,536 bytes (ERR-31) | `413` `REQUEST_TOO_LARGE` |
| A `429` or `503` raised by a handler (ERR-32/33) | `Retry-After`, defaulting to 60 seconds |

Every successful response carries `Content-Language`, `Vary: Accept-Language`, an `ETag`, and a
`Cache-Control` chosen for the resource class — four hours for the Service Document, one hour for
Collections, five minutes for Offerings, and `no-store` for a search. An operation whose advertised
authentication is not `not-required` is marked `private` rather than `public`. Problem responses are
`no-store`.

`Accept-Language` is resolved by the RFC 4647 Lookup scheme against the Service Document's
`localizations`, and a request whose ranges match nothing receives the default representation rather
than a refusal (SVC-59). The selected tag is what `CatalogRequest.language()` carries.

### What the runtime checks before it sends

A handler's response is validated against the resource contract before it leaves: Terse Offerings
carry no `actions`, Full representations carry no `detail_fields`, a page's items do not restate
`odp_version` (VER-03), and a `next` is a bounded origin-relative reference that advances the
traversal rather than repeating the cursor it was given (PAG-06/07/11). A document past 524,288
bytes or 16 levels of nesting, or a Service Document past 65,536 bytes or 8 levels, is refused
(ERR-19). Any of these becomes a `500` `INTERNAL_ERROR` instead of a non-conformant response.

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
