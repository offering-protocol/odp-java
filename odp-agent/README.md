# ODP Agent

Agent-oriented directory-to-Service discovery, live Service inspection, catalog navigation, and
Offering discovery.

Use `OdpAgent` for a bounded convenience search across multiple Services. Use `OdpServiceClient`
when the application already knows a Service origin or needs explicit control over inspection,
capability checks, Collections, Offerings, localization, and continuations.

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-agent` and
exactly one JSON provider.

The Agent module brings in `odp-directory` and `odp-core` transitively. Replace
`odp-json-jackson2` with `odp-json-jackson3` in a Jackson 3 application. Exactly one provider must
be present at runtime; no programmatic configuration is required.

## Discover Offerings across Services

`OdpAgent` searches the canonical directory, inspects each selected Service, and searches Services
that advertise `search-offerings`.

```java
DirectoryClient directory = DirectoryClient.create();
OdpAgent agent = new OdpAgent(directory);

for (OdpAgent.DiscoveryEvent event : agent.searchOfferings("plants", 10, 10)) {
    if (event instanceof OdpAgent.OfferingEvent offering) {
        consume(offering.service(), offering.offering());
    } else if (event instanceof OdpAgent.IssueEvent issue) {
        report(issue.service(), issue.message());
    }
}
```

The second argument limits directory Services and the third limits Offerings retained from each
Service. Both must be from 1 through 100. Results preserve directory order. A failed Service emits
an `IssueEvent`; it does not discard successful results from other Services.

This convenience method does not reinterpret a Service that lacks `search-offerings` as a listing
request. Applications that want that fallback should use `DirectoryClient`, inspect each Service,
and explicitly choose search or list from the advertised operations.

Use the sandbox directory by constructing the Agent with an explicitly selected client:

```java
OdpAgent agent = new OdpAgent(
        DirectoryClient.create(DirectoryEnvironment.SANDBOX));
```

## Inspect one Service

Creating a Service client retrieves `/.well-known/odp`, validates the document, and records the
Service's advertised operations.

```java
OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"));

ServiceInspection inspection = service.inspection();
System.out.println(inspection.document().name());
System.out.println(inspection.document().protocols());

if (inspection.supports(OdpOperation.LIST_OFFERINGS)) {
    Page<Offering> page = service.listOfferings("terse", 25, "en");
    consume(page.items());
}
```

The input may be the Service origin or another URL on that origin. Production Services must use
HTTPS; loopback HTTP is accepted for local development. The client accepts at most five
same-origin redirects and bounds Service Document and catalog response bodies.

The Service Document is fetched once during `OdpServiceClient.create(...)` and retained for that
client's lifetime. Recreate the client when the application needs a refreshed Service Document.
The SDK does not maintain a persistent cache.

## Navigate Collections and Offerings

The client exposes only explicit network operations; it never calls an unadvertised operation.

```java
Page<Collection> collections = service.listCollections("terse", 25, "en");
Collection collection = service.getCollection("indoor-plants", "full", "en");
Page<Offering> members = service.listCollectionOfferings(
        collection.id(), "terse", 25, "en");

Page<Offering> offerings = service.listOfferings("terse", 25, "en");
Offering details = service.getOffering("rubber-plant", "full", "en");
```

Representation is `terse` or `full`; passing `null` selects `terse`. Language is sent through
`Accept-Language` when it is nonblank. Limits must be from 1 through 100.

Search requests preserve the protocol's structured filters, Collection scope, sort identifier,
and refinements:

```java
SearchRequests.Offerings request = new SearchRequests.Offerings(
        Odp.VERSION,
        "indoor plant",
        null,
        "indoor-plants",
        null,
        null,
        null,
        10);

OfferingPage matches = service.searchOfferings(request, "terse", "en");
```

Call `searchCollections(...)` with `SearchRequests.Collections` for Collection search. Check
`inspection.supports(...)` before invoking optional Collection or search operations; the client
also rejects an unsupported call locally.

### Interpret a full Offering

Use `getOfferingDetails(...)` when the application needs the Offering's Service-defined attributes
or Actions:

```java
OfferingDetails details = service.getOfferingDetails("rubber-plant", "en");

if (details.attributeSchema() != null) {
    consume(details.offering().attributes(), details.attributeSchema());
}
for (DiscoveredAction action : details.actions()) {
    System.out.println(action.id() + " " + action.rel());
}
for (OfferingIssue issue : details.issues()) {
    report(issue.scope(), issue.message());
}
```

The Agent retrieves the complete bounded JSON Schema Draft 2020-12 graph, bundles external `$ref`
documents into the returned schema, and validates full Offering attributes. `$dynamicRef` is limited
to fragment references such as `#node`. An unavailable, unsupported, or non-matching schema removes
only the uninterpretable attributes and produces a scoped issue; the Offering remains usable.

Actions are normalized to absolute compact HTTP or OpenAPI targets. Resolve the supporting document
for one explicitly selected Action without invoking it:

```java
ResolvedAction action = service.resolveAction("rubber-plant", "purchase", "en");

if (action.requestSchema() != null) {
    prepareBody(action.action().http(), action.requestSchema());
} else if (action.openApiDocument() != null) {
    prepareOperation(action.action().openapi(), action.operation());
}
```

Compact HTTP request schemas follow the same bounded resolution rules as Attribute Schemas. OpenAPI
targets require a JSON OpenAPI 3.1 document containing exactly one matching `operationId`.

## Continue a response

Continuation values are opaque. Pass `next` unchanged to the matching continuation method:

```java
Page<Offering> page = service.listOfferings("terse", 25, "en");
while (page.next() != null) {
    page = service.continueOfferings(page.next(), "en");
    consume(page.items());
}
```

Use `continueCollections` for Collection pages. `OdpPagination` in Core can collect a bounded
traversal and rejects loops after at most 16 pages. Applications following pages directly should
apply their own total page and item limits.

## Authentication and payment transport

The default client performs anonymous HTTP requests. ODP advertises authentication requirements and
payment protocols but does not implement AEP, MPP, or x402 credentials in this module.

Supply `OdpTransport` when the application needs to control the HTTP stack. This complete example
uses a dedicated JDK client without adding credentials:

```java
HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

OdpTransport transport = request -> httpClient.send(
        request,
        HttpResponse.BodyHandlers.ofByteArray());

OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"),
        transport);
```

The transport receives the complete ODP `HttpRequest` and must return an
`HttpResponse<byte[]>`. An application that supports AEP, MPP, or x402 replaces the lambda with its
protocol-aware transport and performs challenge handling before returning the final response. Keep
credentials scoped to the intended Service and authenticated principal. `OdpAgent` accepts a
`ServiceClientFactory` when federated discovery needs the same custom transport for each Service.

Attribute Schema, Action request-schema, and OpenAPI requests use the default anonymous transport so
credentials added by the catalog transport are not forwarded to supporting-resource origins. Supply
an explicit anonymous supporting transport as the third argument when the application needs to
control that network boundary:

```java
OdpServiceClient service = OdpServiceClient.create(
        URI.create("https://service.example"),
        protocolAwareTransport,
        anonymousSupportingTransport);
```

Supporting-document resolution does not invoke an Action. The application remains responsible for
Action selection, user approval, authentication, payment, request construction, and invocation.

Service inspection filters unrecognized enrollment, payment, and trust protocol descriptors for
compatible ODP versions. Recognized descriptors remain subject to the complete Service Document
contract.

## Errors

Non-success Service responses throw `OdpRequestException`, which preserves the HTTP status,
headers, and parsed ODP Problem Details when supplied. Invalid protocol documents throw
`OdpValidationException`. Invalid local arguments use `IllegalArgumentException`; unsupported
operations and transport-boundary failures use `IllegalStateException`.

## Related documentation

- [Directory integration](../odp-directory/README.md)
- [Core models and validation](../odp-core/README.md)
- [Runnable Agent example](../examples/README.md#agent-discovery)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-agent)
