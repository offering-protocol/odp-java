# ODP JSON for Jackson 3

Jackson 3 encoding, decoding, and JSON Schema validation for `odp-core`.

Add this artifact alongside an ODP role module when the application uses Jackson 3. Follow the
canonical [installation guide](../README.md#installation) for BOM and direct-version usage.

`OdpJson` discovers the provider through Java `ServiceLoader`. Do not also add
`odp-json-jackson2`; exactly one provider must be present at runtime.
