# ODP Core

Transport-independent Offering Discovery Protocol models, validation, identity, resource-reference,
Problem Details, and pagination primitives.

Most Agent and Service applications receive `odp-core` transitively through their role module.
Depend on Core directly when implementing protocol tooling, validating stored documents, or using
ODP models without Agent or Service HTTP behavior.

## Install

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-core</artifactId>
  <version>0.1.1</version>
</dependency>
```

```kotlin
implementation("org.offeringprotocol:odp-core:0.1.1")
```

`odp-core` requires Java 17 or newer and does not depend on another ODP module or an application
framework.

## Validate and decode documents

`OdpJson` validates incoming JSON against the exact ODP schemas bundled in the published JAR before
decoding it into immutable Java models.

```java
try {
    ServiceDocument document = OdpJson.parseServiceDocument(responseBody);
    use(document);
} catch (OdpValidationException exception) {
    for (ValidationIssue issue : exception.issues()) {
        System.err.printf("%s: %s%n", issue.path(), issue.message());
    }
}
```

Typed parsers are available for Service Documents, Collections, Offerings, Offering search
responses, search requests, page envelopes, and ODP Problem Details. `OdpJson.write(value)` encodes
the corresponding Java records while omitting absent optional members. Unknown additive members
permitted by the protocol are retained in each model's `additional` map.

Validation failures are reported as `OdpValidationException` with a document type and structured
issues. Invalid local method arguments use `IllegalArgumentException`.

## Build a Service Document

Use `ServiceDocument.builder(...)` when protocol tooling needs to construct a document directly.
The builder sets the current ODP version and defaults `localizations` to the selected language:

```java
ServiceDocument document = ServiceDocument.builder(
                "Example Plant Store",
                "Indoor plants selected for homes and offices.",
                "en",
                new ServiceDocument.Http("/odp", null))
        .keywords(List.of("plants", "indoor-plants"))
        .operations(operations)
        .build();
```

Service applications should normally use the higher-level `OdpService.builder(...)`, which derives
the operation descriptors from the handlers the application configures.

## Resource identity and references

`ResourceIdentity` composes the Service origin, resource type, and Service-owned identifier into a
stable identity suitable for application storage:

```java
ResourceIdentity identity = ResourceIdentity.create(
        URI.create("https://service.example/.well-known/odp"),
        "offering",
        "gpu-h100");
```

`OdpUris` derives a Service origin, resolves Service-owned resource references, validates opaque
continuations, and builds the fixed URL for an advertised operation. Resource references accept
root-relative paths or secure absolute URLs. Continuations must remain on the Service origin, and
operation identifiers must be safe local path segments.

## Pagination

ODP continuation values are opaque. Pass each `next` value unchanged to the appropriate page
loader:

```java
Page<Offering> first = client.listOfferings("terse", 25, "en");
List<Offering> offerings = OdpPagination.items(
        first,
        next -> client.continueOfferings(next, "en"));
```

`OdpPagination` detects continuation loops and limits one traversal to 16 pages. Applications that
need independent cancellation, streaming, or a lower result ceiling can follow pages directly and
stop before invoking the next loader.

## Payment option vocabulary

`PaymentOption` contains the closed human-facing option vocabulary that a Service can advertise for
MPP or x402, such as `INFLOW`, `SOLANA`, or `BASE`. These values summarize compatibility for
discovery and filtering. Live MPP and x402 responses remain authoritative for exact payment terms.

`ServiceDocument.TrustProtocol` represents advertised trust support. A Service that accepts Visa
Trusted Agent Protocol requests declares a single `tap` descriptor in `protocols.trust`.

## Related documentation

- [Agent integration](../odp-agent/README.md)
- [Directory integration](../odp-directory/README.md)
- [Service integration](../odp-service/README.md)
- [Normative ODP specifications](https://www.offeringprotocol.org/)
- [Maven Central artifact](https://central.sonatype.com/artifact/org.offeringprotocol/odp-core)
