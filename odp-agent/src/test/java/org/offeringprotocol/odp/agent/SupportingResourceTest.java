package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SupportingResourceTest {
    private static final URI ROOT = URI.create("https://schemas.example/root.json");
    private static final String SCHEMA_TYPE = "application/schema+json";
    private static final String SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}";

    @Test
    void refusesASupportingUrlThatIsNotHttps() {
        for (String target : List.of("http://schemas.example/root.json", "file:///schema.json")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> client(request ->
                                    Responses.of(request, 200, SCHEMA, Map.of("Content-Type", List.of(SCHEMA_TYPE))))
                            .get(URI.create(target), SCHEMA_TYPE, Set.of(SCHEMA_TYPE), 1024, 16));
            assertEquals("ODP supporting document URL must use HTTPS", failure.getMessage());
        }
    }

    @Test
    void refusesWhatASupportingResponseCannotBe() {
        Map<String, String> cases = new HashMap<>();
        cases.put("ODP supporting resource returned an unsupported Content-Type", "application/json");
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> get(request ->
                            Responses.of(request, 200, SCHEMA, Map.of("Content-Type", List.of(entry.getValue())))));
            assertEquals(entry.getKey(), failure.getMessage());
        }
        assertEquals(
                "ODP supporting resource request failed with HTTP 404",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request ->
                                        Responses.of(request, 404, "", Map.of("Content-Type", List.of(SCHEMA_TYPE)))))
                        .getMessage());
        assertEquals(
                "ODP supporting resource exceeds its byte limit",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request,
                                        200,
                                        "{\"$schema\":\"x\",\"pad\":\"" + "p".repeat(2_000) + "\"}",
                                        Map.of("Content-Type", List.of(SCHEMA_TYPE)))))
                        .getMessage());
        assertEquals(
                "ODP supporting resource must contain valid JSON",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request, 200, "{not json", Map.of("Content-Type", List.of(SCHEMA_TYPE)))))
                        .getMessage());
        assertEquals(
                "ODP supporting resource must be a JSON object",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request, 200, "[1,2,3]", Map.of("Content-Type", List.of(SCHEMA_TYPE)))))
                        .getMessage());
        String tower = "[".repeat(20) + "0" + "]".repeat(20);
        assertEquals(
                "ODP supporting resource exceeds its JSON depth limit",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request,
                                        200,
                                        "{\"tower\":" + tower + "}",
                                        Map.of("Content-Type", List.of(SCHEMA_TYPE)))))
                        .getMessage());
    }

    @Test
    void followsSameOriginRedirectsAndRefusesTheRest() {
        int[] hops = {0};
        assertEquals(
                SCHEMA,
                get(request -> {
                            hops[0]++;
                            if (hops[0] == 1) {
                                return Responses.of(
                                        request,
                                        302,
                                        "",
                                        Map.of("Location", List.of("https://schemas.example/moved.json")));
                            }
                            return Responses.of(request, 200, SCHEMA, Map.of("Content-Type", List.of(SCHEMA_TYPE)));
                        })
                        .toString());
        assertEquals(2, hops[0]);

        assertEquals(
                "ODP supporting resource redirect changed origin",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request,
                                        302,
                                        "",
                                        Map.of("Location", List.of("https://elsewhere.example/root.json")))))
                        .getMessage());
        assertEquals(
                "ODP supporting resource redirect omitted Location",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(request, 302, "", Map.of())))
                        .getMessage());
        assertEquals(
                "ODP supporting resource exceeded its redirect limit",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> Responses.of(
                                        request,
                                        302,
                                        "",
                                        Map.of("Location", List.of("https://schemas.example/again")))))
                        .getMessage());
        assertEquals(
                "ODP supporting document URL must use HTTPS",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> get(request -> Responses.of(
                                        request,
                                        302,
                                        "",
                                        Map.of("Location", List.of("http://schemas.example/root.json")))))
                        .getMessage());
    }

    @Test
    void reportsATransportThatCouldNotAnswer() {
        assertEquals(
                "ODP supporting resource request failed",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> {
                                    throw new java.io.IOException("connection reset by peer");
                                }))
                        .getMessage());
        assertEquals(
                "ODP supporting resource request was interrupted",
                assertThrows(
                                IllegalStateException.class,
                                () -> get(request -> {
                                    throw new InterruptedException("stopped");
                                }))
                        .getMessage());
        assertTrue(Thread.interrupted());
    }

    private static SupportingJsonClient client(Transport transport) {
        return new SupportingJsonClient(transport::send);
    }

    private static Object get(Transport transport) {
        return client(transport).get(ROOT, SCHEMA_TYPE, Set.of(SCHEMA_TYPE), 1024, 16);
    }

    @FunctionalInterface
    private interface Transport {
        HttpResponse<byte[]> send(HttpRequest request) throws java.io.IOException, InterruptedException;
    }
}
