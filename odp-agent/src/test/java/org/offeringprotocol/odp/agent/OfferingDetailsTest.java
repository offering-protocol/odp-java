package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class OfferingDetailsTest {
    private static final String SERVICE_DOCUMENT = """
            {"odp_version":"1.0","name":"Plant Store","description":"Plants for agents.",
            "language":"en","localizations":["en"],"operations":[
            {"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-offerings"}],
            "http":{"endpoint_base":"/odp","openapi":{"url":"/openapi.json"}}}
            """;
    private static final String OFFERING = """
            {"odp_version":"1.0","id":"gpu","name":"GPU","attributes":{"memory":80},
            "schema":{"url":"https://schemas.example/root.json"},"actions":[
            {"authentication":"not-required","id":"purchase","rel":"purchase","http":{
              "href":"/purchase","method":"POST","request":{
                "content_type":"application/json","schema":{"url":"https://schemas.example/request.json"}}}},
            {"authentication":"required","id":"quote","rel":"quote","openapi":{
              "operation_id":"quoteGpu"}}]}
            """;
    private static final String ROOT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
            "$id":"https://schemas.example/root.json",
            "$ref":"https://schemas.example/common.json"}
            """;
    private static final String COMMON_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
            "$id":"https://schemas.example/common.json","$dynamicAnchor":"node","type":"object",
            "properties":{"memory":{"type":"integer","minimum":1},"children":{"type":"array",
            "items":{"$dynamicRef":"#node"}}},"required":["memory"]}
            """;
    private static final String REQUEST_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
            "type":"object","properties":{"hours":{"type":"integer"}},"required":["hours"]}
            """;
    private static final String OPENAPI = """
            {"openapi":"3.1.1","info":{"title":"Plant API","version":"1"},"paths":{
            "/quotes":{"post":{"operationId":"quoteGpu","responses":{"200":{"description":"Quote"}}}}}}
            """;

    @Test
    void resolvesOfferingSchemaAndNormalizesActions() {
        Fixture fixture = fixture(OFFERING, ROOT_SCHEMA);

        OfferingDetails details = fixture.client().getOfferingDetails("gpu", "en");

        assertEquals(80, details.offering().attributes().get("memory").asInt());
        assertNotNull(details.attributeSchema().get("$defs"));
        assertTrue(details.issues().isEmpty());
        assertEquals(
                "https://plants.example/purchase",
                details.actions().get(0).http().url());
        assertEquals(
                "https://plants.example/openapi.json",
                details.actions().get(1).openapi().url());
        assertTrue(fixture.supportingRequests().stream()
                .allMatch(
                        request -> request.headers().firstValue("Authorization").isEmpty()));
    }

    @Test
    void omitsAttributesThatDoNotMatchTheirSchema() {
        Fixture fixture = fixture(OFFERING.replace("\"memory\":80", "\"memory\":\"large\""), ROOT_SCHEMA);

        OfferingDetails details = fixture.client().getOfferingDetails("gpu", null);

        assertNull(details.offering().attributes());
        assertNotNull(details.attributeSchema());
        assertEquals(OfferingIssue.Scope.ATTRIBUTES, details.issues().get(0).scope());
    }

    @Test
    void retainsOfferingWhenDynamicReferenceIsUnsupported() {
        for (String dynamicReference :
                List.of("\"https://schemas.example/common.json#node\"", "\"common.json#node\"", "null")) {
            String unsupported = """
                    {"$schema":"https://json-schema.org/draft/2020-12/schema","$dynamicRef":%s}
                    """.formatted(dynamicReference);
            Fixture fixture = fixture(OFFERING, unsupported);

            OfferingDetails details = fixture.client().getOfferingDetails("gpu", null);

            assertEquals("GPU", details.offering().name());
            assertNull(details.offering().attributes());
            assertNull(details.attributeSchema());
            assertEquals(
                    OfferingIssue.Scope.ATTRIBUTE_SCHEMA,
                    details.issues().get(0).scope());
        }
    }

    @Test
    void resolvesHttpRequestSchemaAndOpenApiOperation() {
        Fixture fixture = fixture(OFFERING, ROOT_SCHEMA);

        ResolvedAction purchase = fixture.client().resolveAction("gpu", "purchase", null);
        ResolvedAction quote = fixture.client().resolveAction("gpu", "quote", null);

        assertEquals("object", purchase.requestSchema().get("type").asString());
        assertNull(purchase.openApiDocument());
        assertEquals("3.1.1", quote.openApiDocument().get("openapi").asString());
        assertEquals("quoteGpu", quote.operation().get("operationId").asString());
    }

    @Test
    void reportsDuplicateAndUnusableActionsWithoutRejectingOffering() {
        String invalidActions = OFFERING.replace(
                "]}",
                ",{" + "\"authentication\":\"not-required\",\"id\":\"purchase\","
                        + "\"rel\":\"purchase\",\"http\":{\"href\":\"/other\",\"method\":\"POST\"}}]}");
        Fixture fixture = fixture(invalidActions, ROOT_SCHEMA);

        OfferingDetails details = fixture.client().getOfferingDetails("gpu", null);

        assertEquals("GPU", details.offering().name());
        assertFalse(details.actions().stream().anyMatch(action -> action.id().equals("purchase")));
        assertEquals("purchase", details.issues().get(0).actionId());
    }

    private static Fixture fixture(String offering, String rootSchema) {
        OdpTransport service = request -> response(
                request,
                switch (request.uri().getPath()) {
                    case "/.well-known/odp" -> SERVICE_DOCUMENT;
                    case "/odp/offerings/gpu" -> offering;
                    default -> throw new AssertionError("Unexpected Service request " + request.uri());
                },
                "application/odp+json");
        List<HttpRequest> requests = new ArrayList<>();
        OdpTransport supporting = request -> {
            requests.add(request);
            String body;
            String contentType;
            switch (request.uri().toString()) {
                case "https://schemas.example/root.json" -> {
                    body = rootSchema;
                    contentType = "application/schema+json";
                }
                case "https://schemas.example/common.json" -> {
                    body = COMMON_SCHEMA;
                    contentType = "application/schema+json";
                }
                case "https://schemas.example/request.json" -> {
                    body = REQUEST_SCHEMA;
                    contentType = "application/schema+json";
                }
                case "https://plants.example/openapi.json" -> {
                    body = OPENAPI;
                    contentType = "application/vnd.oai.openapi+json;version=3.1";
                }
                default -> throw new AssertionError("Unexpected supporting request " + request.uri());
            }
            return response(request, body, contentType);
        };
        return new Fixture(
                OdpServiceClient.create(URI.create("https://plants.example"), service, supporting), requests);
    }

    private static HttpResponse<byte[]> response(HttpRequest request, String body, String contentType) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (left, right) -> true);
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private record Fixture(OdpServiceClient client, List<HttpRequest> supportingRequests) {}
}
