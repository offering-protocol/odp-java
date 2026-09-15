# ODP Directory

The official Java client for discovering candidate Services through the one canonical ODP
directory. It searches cached Service metadata; it does not search the complete catalogs owned by
those Services.

After directory discovery, an Agent inspects each candidate's live ODP document and queries the
Service's Collections and Offerings with [`odp-agent`](../odp-agent/README.md).

## Install

Follow the canonical [installation guide](../README.md#installation), selecting `odp-directory`
and exactly one JSON provider.

Replace `odp-json-jackson2` with `odp-json-jackson3` in a Jackson 3 application. Add exactly one
provider; it is discovered automatically at runtime.

## Search Services

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

## What the client checks in a result

A directory result is a third party's description of somebody else's Service, and an Agent connects
to whatever `service_origin` names, so each result is checked before it is handed over:

- `service_origin` must be the ASCII serialization of a secure origin — `https`, a lowercase host,
  no port, path, query, fragment, or user information — naming a destination the public internet
  routes. An address in the IANA special-purpose registries is refused, including the IPv6
  transition ranges that embed an IPv4 address.
- The rest of the result is held to the shape of the Service Document it summarizes.
- `branding`, `http`, `mcp`, `odp_version`, `payment_origins`, and `search_capabilities` are
  removed. A directory summarizes a Service; it does not serve the Service's own document, so these
  are not passed on as though the Agent had retrieved them. Retrieve them from the Service with
  [`odp-agent`](../odp-agent/README.md).

One unusable result does not discard the page it arrived on. It is dropped from `page.items()` and
reported in `page.issues()`, whose `index` is the result's position in the page as the directory
sent it:

```java
for (DirectoryModels.Issue issue : page.issues()) {
    System.out.printf("result %d was dropped: %s%n", issue.index(), issue.message());
}
```

## Continue a search

One call returns one page. When `page.next()` is non-null, submit that opaque value unchanged:

```java
while (page.next() != null) {
    page = directory.continueSearchServices(page.next());
    consume(page.items());
}
```

The client retrieves continuations with GET, keeps them on the selected canonical origin (a
written-out default port still matches), limits redirects to five, and bounds response bodies —
524,288 bytes for a success and 16,384 for an error, refused on the declared `Content-Length`
before the body is read. Applications should impose their own total page and item limit when
following multiple pages.

## Keyword suggestions

Suggestions let an Agent discover useful keyword vocabulary by prefix:

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
response headers. Its message describes the request that failed; it quotes the response only when
that response is a JSON error document, and then only its `detail`, `title`, or `message` member,
flattened and truncated so an error body cannot forge a log line. Invalid arguments and malformed
successful responses use `IllegalArgumentException`; transport, interruption, redirect, and
response-boundary failures use `IllegalStateException`.

## Related documentation

- [Agent integration](../odp-agent/README.md)
- [Core models and validation](../odp-core/README.md)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-directory)
