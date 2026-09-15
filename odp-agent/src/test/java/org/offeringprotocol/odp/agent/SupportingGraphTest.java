package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupportingGraphTest {
    private static final URI SERVICE = URI.create("https://plants.example");
    private static final String SCHEMA_TYPE = "application/schema+json";
    private static final String OPENAPI_TYPE = "application/vnd.oai.openapi+json;version=3.1";
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    private static String offering(String members) {
        return "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\"" + members + "}";
    }

    private static String schemaOffering() {
        return offering(",\"schema\":{\"url\":\"https://schemas.example/root.json\"}");
    }

    /** A schema graph is bounded by document count, reference depth and total bytes. */
    @Test
    void refusesASchemaGraphPastItsBounds() {
        Map<String, String> chain = new HashMap<>();
        for (int index = 0; index < 20; index++) {
            chain.put(
                    "https://schemas.example/n" + index + ".json",
                    "{\"$schema\":\"" + DIALECT + "\",\"$ref\":\"n" + (index + 1) + ".json\"}");
        }
        chain.put("https://schemas.example/root.json", "{\"$schema\":\"" + DIALECT + "\",\"$ref\":\"n0.json\"}");
        assertEquals(
                "ODP Attribute Schema graph exceeds eight reference levels",
                issue(schemaOffering(), chain).message());

        Map<String, String> wide = new HashMap<>();
        StringBuilder references = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            references
                    .append(index == 0 ? "" : ",")
                    .append("\"p")
                    .append(index)
                    .append("\":{\"$ref\":\"w")
                    .append(index)
                    .append(".json\"}");
            wide.put("https://schemas.example/w" + index + ".json", "{\"$schema\":\"" + DIALECT + "\"}");
        }
        wide.put(
                "https://schemas.example/root.json",
                "{\"$schema\":\"" + DIALECT + "\",\"properties\":{" + references + "}}");
        assertEquals(
                "ODP Attribute Schema graph exceeds 16 documents",
                issue(schemaOffering(), wide).message());

        Map<String, String> heavy = new HashMap<>();
        heavy.put(
                "https://schemas.example/root.json",
                "{\"$schema\":\"" + DIALECT + "\",\"properties\":{\"a\":{\"$ref\":\"big0.json\"},"
                        + "\"b\":{\"$ref\":\"big1.json\"},\"c\":{\"$ref\":\"big2.json\"},"
                        + "\"d\":{\"$ref\":\"big3.json\"},\"e\":{\"$ref\":\"big4.json\"}}}");
        for (int index = 0; index < 5; index++) {
            heavy.put(
                    "https://schemas.example/big" + index + ".json",
                    "{\"$schema\":\"" + DIALECT + "\",\"description\":\"" + "d".repeat(250_000) + "\"}");
        }
        assertEquals(
                "ODP Attribute Schema graph exceeds its byte limit",
                issue(schemaOffering(), heavy).message());
    }

    @Test
    void refusesASchemaItCannotTrust() {
        assertEquals(
                "ODP Attribute Schema must declare JSON Schema Draft 2020-12",
                issue(schemaOffering(), Map.of("https://schemas.example/root.json", "{\"type\":\"object\"}"))
                        .message());
        assertEquals(
                "ODP Attribute Schema requires unsupported vocabulary https://example.com/vocab/custom",
                issue(
                                schemaOffering(),
                                Map.of(
                                        "https://schemas.example/root.json",
                                        "{\"$schema\":\"" + DIALECT
                                                + "\",\"$defs\":{\"inner\":{\"$vocabulary\":{\"https://example.com/vocab/custom\":true}}}}"))
                        .message());
        assertEquals(
                "ODP Attribute Schema $dynamicRef must be a fragment-only reference",
                issue(
                                schemaOffering(),
                                Map.of(
                                        "https://schemas.example/root.json",
                                        "{\"$schema\":\"" + DIALECT + "\",\"$dynamicRef\":\"other.json#node\"}"))
                        .message());
        assertEquals(
                "ODP Attribute Schema $ref must be a string",
                issue(
                                schemaOffering(),
                                Map.of(
                                        "https://schemas.example/root.json",
                                        "{\"$schema\":\"" + DIALECT + "\",\"$defs\":{\"inner\":{\"$ref\":7}}}"))
                        .message());
        assertEquals(
                "ODP Attribute Schema $id must be a string",
                issue(
                                schemaOffering(),
                                Map.of(
                                        "https://schemas.example/root.json",
                                        "{\"$schema\":\"" + DIALECT + "\",\"$defs\":{\"inner\":{\"$id\":7}}}"))
                        .message());
        assertEquals(
                "ODP Attribute Schema references must use HTTPS",
                issue(
                                schemaOffering(),
                                Map.of(
                                        "https://schemas.example/root.json",
                                        "{\"$schema\":\"" + DIALECT + "\",\"$ref\":\"http://schemas.example/x.json\"}"))
                        .message());
    }

    /** A graph is retrieved once per document, and the bundle keeps definitions the root already had. */
    @Test
    void bundlesAGraphWithoutLosingTheRootsOwnDefinitions() {
        Map<String, Integer> requests = new HashMap<>();
        Map<String, String> documents = Map.of(
                "https://schemas.example/root.json",
                "{\"$schema\":\"" + DIALECT + "\",\"$defs\":{\"odp_external_0\":{\"type\":\"string\"}},"
                        + "\"properties\":{\"left\":{\"$ref\":\"shared.json\"},\"right\":{\"$ref\":\"shared.json\"}}}",
                "https://schemas.example/shared.json",
                "{\"$schema\":\"" + DIALECT + "\",\"type\":\"integer\"}");
        OdpServiceClient client = OdpServiceClient.create(
                SERVICE,
                request -> Responses.ok(
                        request,
                        request.uri().getPath().equals("/.well-known/odp")
                                ? Responses.SERVICE_DOCUMENT
                                : schemaOffering()),
                request -> {
                    requests.merge(request.uri().toString(), 1, Integer::sum);
                    return Responses.of(
                            request,
                            200,
                            documents.get(request.uri().toString()),
                            Map.of("Content-Type", List.of(SCHEMA_TYPE)));
                });
        OfferingDetails details = client.getOfferingDetails("gpu", null);
        assertTrue(details.issues().isEmpty(), details.issues().toString());
        assertEquals(1, requests.get("https://schemas.example/shared.json"));
        String bundled = details.attributeSchema().toString();
        assertTrue(bundled.contains("odp_external_0_"), bundled);
        assertTrue(bundled.contains("\"type\":\"string\""), bundled);
    }

    @Test
    void reportsAnOfferingWithNoSchemaAndNoServiceOpenApi() {
        OdpServiceClient client = Responses.serving(request -> offering(""));
        OfferingDetails details = client.getOfferingDetails("gpu", null);
        assertNull(details.attributeSchema());
        assertTrue(details.actions().isEmpty());
        assertTrue(details.issues().isEmpty());
    }

    /** An Action the Agent cannot use is reported rather than offered. */
    @Test
    void reportsAnActionItCannotUse() {
        assertEquals(
                "OpenAPI Action has no OpenAPI document URL",
                actionIssue("{\"authentication\":\"not-required\",\"id\":\"rent\",\"rel\":\"purchase\","
                        + "\"openapi\":{\"operation_id\":\"rent\"}}"));
        assertEquals(
                "ODP supporting document URL must use HTTPS",
                actionIssue("{\"authentication\":\"not-required\",\"id\":\"rent\",\"rel\":\"purchase\","
                        + "\"openapi\":{\"operation_id\":\"rent\",\"url\":\"http://localhost:8080/openapi.json\"}}"));
    }

    @Test
    void resolvesOnlyAnActionTheOfferingExposes() {
        OdpServiceClient client = Responses.serving(request ->
                offering(",\"actions\":[{\"authentication\":\"not-required\",\"id\":\"rent\",\"rel\":\"purchase\","
                        + "\"http\":{\"href\":\"/rent\",\"method\":\"POST\"}}]"));
        ResolvedAction resolved = client.resolveAction("gpu", "rent", null);
        assertEquals("https://plants.example/rent", resolved.action().http().url());
        // An HTTP Action with no request schema resolves to the target alone.
        assertNull(resolved.requestSchema());
        assertNull(resolved.openApiDocument());
        assertEquals(
                "ODP Offering does not expose usable Action absent",
                assertThrows(IllegalArgumentException.class, () -> client.resolveAction("gpu", "absent", null))
                        .getMessage());
    }

    @Test
    void refusesAnOpenApiDocumentItCannotUse() {
        String action = "{\"authentication\":\"not-required\",\"id\":\"rent\",\"rel\":\"purchase\","
                + "\"openapi\":{\"operation_id\":\"rent\",\"url\":\"https://api.example/openapi.json\"}}";
        assertEquals(
                "ODP Action requires an OpenAPI 3.1 document",
                resolveFailure(action, "{\"openapi\":\"3.0.0\",\"paths\":{}}"));
        assertEquals("ODP OpenAPI document must contain paths", resolveFailure(action, "{\"openapi\":\"3.1.0\"}"));
        assertEquals(
                "ODP Action operation_id rent must resolve exactly once",
                resolveFailure(
                        action, "{\"openapi\":\"3.1.0\",\"paths\":{\"/a\":{\"post\":{\"operationId\":\"other\"}}}}"));
        assertEquals(
                "ODP Action operation_id rent must resolve exactly once",
                resolveFailure(
                        action,
                        "{\"openapi\":\"3.1.0\",\"paths\":{\"/a\":{\"post\":{\"operationId\":\"rent\"}},"
                                + "\"/b\":{\"get\":{\"operationId\":\"rent\"}}}}"));
        // A path member that is not an object, and a method ODP does not read, are both passed over.
        assertEquals(
                "rent",
                resolved(
                                action,
                                "{\"openapi\":\"3.1.0\",\"paths\":{\"/a\":7,\"/b\":{\"summary\":\"x\","
                                        + "\"post\":{\"operationId\":\"rent\"}}}}")
                        .operation()
                        .path("operationId")
                        .asString());
    }

    @Test
    void buildsATransportForEachDestinationPolicy() {
        assertNotNull(OdpServiceClient.defaultTransport());
        assertNotNull(OdpServiceClient.localDevelopmentTransport());
        // create(URI) goes through the public-destination transport, which refuses loopback.
        assertThrows(IllegalStateException.class, () -> OdpServiceClient.create(URI.create("https://127.0.0.1")));
    }

    private static OfferingIssue issue(String offering, Map<String, String> documents) {
        OdpServiceClient client = client(offering, documents, Map.of());
        List<OfferingIssue> issues = client.getOfferingDetails("gpu", null).issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    private static String actionIssue(String action) {
        OdpServiceClient client = Responses.serving(request -> offering(",\"actions\":[" + action + "]"));
        List<OfferingIssue> issues = client.getOfferingDetails("gpu", null).issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0).message();
    }

    private static String resolveFailure(String action, String openapi) {
        OdpServiceClient client =
                client(offering(",\"actions\":[" + action + "]"), Map.of(), openapiDocuments(openapi));
        return assertThrows(IllegalStateException.class, () -> client.resolveAction("gpu", "rent", null))
                .getMessage();
    }

    private static ResolvedAction resolved(String action, String openapi) {
        return client(offering(",\"actions\":[" + action + "]"), Map.of(), openapiDocuments(openapi))
                .resolveAction("gpu", "rent", null);
    }

    private static Map<String, String> openapiDocuments(String openapi) {
        return Map.of("https://api.example/openapi.json", openapi);
    }

    private static OdpServiceClient client(
            String offering, Map<String, String> schemas, Map<String, String> openapiDocuments) {
        return OdpServiceClient.create(
                SERVICE,
                request -> Responses.ok(
                        request,
                        request.uri().getPath().equals("/.well-known/odp") ? Responses.SERVICE_DOCUMENT : offering),
                request -> {
                    String target = request.uri().toString();
                    if (openapiDocuments.containsKey(target)) {
                        return Responses.of(
                                request,
                                200,
                                openapiDocuments.get(target),
                                Map.of("Content-Type", List.of(OPENAPI_TYPE)));
                    }
                    String document = schemas.get(target);
                    if (document == null) {
                        return Responses.of(request, 404, "", Map.of("Content-Type", List.of(SCHEMA_TYPE)));
                    }
                    return Responses.of(request, 200, document, Map.of("Content-Type", List.of(SCHEMA_TYPE)));
                });
    }
}
