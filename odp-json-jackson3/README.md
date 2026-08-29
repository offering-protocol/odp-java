# ODP JSON for Jackson 3

Jackson 3 encoding, decoding, and JSON Schema validation for `odp-core`.

Add this artifact alongside an ODP role module when the application uses Jackson 3:

```xml
<dependency>
  <groupId>org.offeringprotocol</groupId>
  <artifactId>odp-json-jackson3</artifactId>
  <version>0.2.0</version>
</dependency>
```

`OdpJson` discovers the provider through Java `ServiceLoader`. Do not also add
`odp-json-jackson2`; exactly one provider must be present at runtime.
