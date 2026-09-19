# ODP Directory

The official Java client for discovering indexed Services and submitted Collections through the
canonical Directory. It does not crawl catalogs or index Offerings.

After directory discovery, an Agent inspects each candidate's live ODP document and queries the
Service's Collections and Offerings with [`odp-agent`](../odp-agent/README.md).

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-directory`
and exactly one JSON provider.

Replace `odp-json-jackson2` with `odp-json-jackson3` in a Jackson 3 application. Add exactly one
provider; it is discovered automatically at runtime.

## Search Services and Collections

```java
DirectoryClient directory = DirectoryClient.create();
DirectoryModels.SearchResponse response = directory.search(
        new DirectoryModels.ResourceSearchRequest("weather forecast", null, 25, null));

for (DirectoryModels.Result result : response.items()) {
    if (result instanceof DirectoryModels.ServiceResult service) {
        System.out.printf("Service: %s (%s)%n", service.service().name(), service.service().serviceOrigin());
    } else if (result instanceof DirectoryModels.CollectionResult collection) {
        System.out.printf("Collection: %s, ID %s, through %s%n",
                collection.collection().name(), collection.collection().id(), collection.service().serviceOrigin());
    } else if (result instanceof DirectoryModels.UnknownResult unknown) {
        System.out.printf("Unsupported result type: %s%n", unknown.type());
    }
}
```

The fourth request argument is an optional list of `"service"` and/or `"collection"`; null selects
both. Explicit lists must be nonempty and distinct. Filters use the owning Service's metadata.

A Collection's identity is its owning Service origin plus its case-sensitive `collection().id()`.
Inspect that Service's live document and use `OdpServiceClient.getCollection` to retrieve current
details. `indexedAt()` on the result records Collection freshness, while `service().indexedAt()`
records the parent's freshness. `service().serviceId()` identifies the local Directory Service.
For Service results, optional `availableThrough()` identifies a platform. Collection attribution
is its owning `service()`.

Known types are validated; a malformed item is omitted and reported in `response.issues()` with
its original index and reason. Other valid items remain available. Unknown future types retain
their wire type and full JSON in `UnknownResult.raw()`; do not treat them as Services or execute
their metadata. Additive fields are retained in `additional()` maps. Execution metadata from the
Directory is not authoritative: obtain current operation paths from the Service itself.

Mixed search returns at most 100 results (the default limit), without continuation. An absent
`next()` does not promise all matches were returned. Refine the query or filters when needed.
`continueSearch(next)` supports an opaque same-origin continuation if the server supplies one;
the SDK never invents a continuation. Each call returns one response, without automatic traversal.

Mixed facets count all matching targets, not just the returned subset: one Service and two
Collections count as three. Collection search is independent of permission to show its card on
the Directory landing page.

See the [runnable canonical discovery example](../examples/README.md#canonical-directory-discovery).

## Search only Services

`DirectoryClient.create()` uses the fixed production directory. Search accepts natural-language
text, deterministic filters, or both.

```java
import java.util.List;
import org.offeringprotocol.odp.core.PaymentOption;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.directory.DirectoryModels;

DirectoryClient directory = DirectoryClient.create();

DirectoryModels.ServiceFilters filters = new DirectoryModels.ServiceFilters(
        null,
        List.of("gpu", "accelerator"),
        null,
        List.of(new DirectoryModels.PaymentFilter(
                null,
                "mpp",
                List.of(PaymentOption.INFLOW, PaymentOption.SOLANA))));

DirectoryModels.SearchPage page = directory.searchServices(
        new DirectoryModels.SearchRequest("compute", filters, 25));

for (DirectoryModels.Service service : page.items()) {
    System.out.printf("%s: %s%n", service.name(), service.serviceOrigin());
}
```

Options within one payment filter are alternatives. The example matches Services that accept
either InFlow or Solana through MPP. A payment filter with no options matches any Service that
advertises that payment protocol.

The response includes structured facets for enrollment protocols, keywords, operations, payment
protocols, payment options, and trust protocols. Use them to refine a user or Agent query without
downloading a global vocabulary.

Compatible results may advertise protocol names unknown to this library. The client filters those
descriptors and preserves recognized enrollment, payment, and trust descriptors, including TAP.

## Continue a search

One call returns one page. When `page.next()` is non-null, submit that opaque value unchanged:

```java
while (page.next() != null) {
    page = directory.continueSearchServices(page.next());
    consume(page.items());
}
```

The client retrieves continuations with GET, keeps them on the selected canonical origin, limits
redirects to five, and bounds response bodies. Applications should impose their own total page and
item limit when following multiple pages.

## Suggestions

```java
List<String> names = directory.suggest("we", 10);
```

`suggest` matches indexed names, descriptions and keywords, and returns **names of matching
Services and Collections**, not the text that matched. Despite the `prefix` argument name,
matching uses substrings and whitespace-separated alternative terms. The server deduplicates
names and returns at most 25 (also the default). These strings are candidate queries, not resource
identifiers. They can be passed to `search`. Collection surfacing permission does not restrict them.

`suggestServices` retains Service-only keyword-prefix suggestions:

```java
List<String> suggestions = directory.suggestServices("gp", 5);
```

The prefix must contain from 1 through 128 characters; the optional limit must be from 1 through
25.

## Sandbox and HTTP policy

Select the fixed sandbox directory explicitly:

```java
DirectoryClient directory = DirectoryClient.create(DirectoryEnvironment.SANDBOX);
```

| Environment | Canonical origin               |
| ----------- | ------------------------------ |
| Production  | `https://api.inflowpay.ai`     |
| Sandbox     | `https://sandbox.inflowpay.ai` |

Callers cannot configure another directory origin. The overload accepting `HttpClient` supports
application transport policy and testing while preserving the selected canonical origin:

```java
DirectoryClient directory = DirectoryClient.create(
        DirectoryEnvironment.PRODUCTION,
        applicationHttpClient);
```

The client does not persist or cache directory responses.

## Errors

Non-success HTTP responses throw `DirectoryRequestException`, which preserves the status and
response headers. Invalid arguments and malformed successful responses use
`IllegalArgumentException`; transport, interruption, redirect, and response-boundary failures use
`IllegalStateException`.

## Related documentation

- [Agent integration](../odp-agent/README.md)
- [Core models and validation](../odp-core/README.md)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-directory)
