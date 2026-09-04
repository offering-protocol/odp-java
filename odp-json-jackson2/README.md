# ODP JSON for Jackson 2

Jackson 2 encoding, decoding, and JSON Schema validation for `odp-core`.

Add this artifact alongside an ODP role module when the application uses Jackson 2. Follow the
canonical [installation guide](../README.md#installation) for BOM and direct-version usage.

`OdpJson` discovers the provider through Java `ServiceLoader`. Do not also add
`odp-json-jackson3`; exactly one provider must be present at runtime.
