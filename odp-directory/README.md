# ODP Directory

The official Java client for discovering candidate Services through the one canonical ODP
directory. It searches cached Service metadata; it does not search the complete catalogs owned by
those Services.

After directory discovery, an Agent inspects each candidate's live ODP document and queries the
Service's Collections and Offerings with [`odp-agent`](../odp-agent/README.md).

## Install

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-directory</artifactId>
  <version>0.1.1</version>
</dependency>
```

```kotlin
implementation("org.offeringprotocol:odp-directory:0.1.1")
```

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
protocols, and payment options. Use them to refine a user or Agent query without downloading a
global vocabulary.

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
response headers. Invalid arguments and malformed successful responses use
`IllegalArgumentException`; transport, interruption, redirect, and response-boundary failures use
`IllegalStateException`.

## Related documentation

- [Agent integration](../odp-agent/README.md)
- [Core models and validation](../odp-core/README.md)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-directory)
