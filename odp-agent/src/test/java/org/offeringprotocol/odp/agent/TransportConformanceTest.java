package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.SearchRequests;

class TransportConformanceTest {
    private static final URI SERVICE = URI.create("https://plants.example");
    private static final String EMPTY_PAGE = "{\"odp_version\":\"1.0\",\"items\":[]}";

    /** ERR-21: the Service Document is read under 65,536 bytes, a catalog response under 524,288. */
    @Test
    void refusesADocumentPastItsByteLimit() {
        String padded = padded(Responses.SERVICE_DOCUMENT, 70_000);
        IllegalStateException document = assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(SERVICE, request -> Responses.ok(request, padded)));
        assertEquals("ODP response exceeds its byte limit", document.getMessage());

        String page = padded(EMPTY_PAGE, 600_000);
        OdpServiceClient client = Responses.serving(request -> page);
        IllegalStateException listing =
                assertThrows(IllegalStateException.class, () -> client.listOfferings(null, null, null));
        assertEquals("ODP response exceeds its byte limit", listing.getMessage());
    }

    /** A Service Document that would be a legal catalog page is still too large to be a document. */
    @Test
    void holdsTheServiceDocumentToItsOwnBudget() {
        String padded = padded(Responses.SERVICE_DOCUMENT, 70_000);
        assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(SERVICE, request -> Responses.ok(request, padded)));
        OdpServiceClient client = Responses.serving(request -> padded(EMPTY_PAGE, 70_000));
        assertEquals(0, client.listOfferings(null, null, null).items().size());
    }

    /** ERR-20: a declared length past the limit is refused before the body is read. */
    @Test
    void refusesADeclaredLengthPastTheLimit() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(
                        SERVICE,
                        request -> Responses.of(
                                request,
                                200,
                                Responses.SERVICE_DOCUMENT,
                                Map.of(
                                        "Content-Type",
                                        List.of(Responses.ODP),
                                        "Content-Length",
                                        List.of("10000000")))));
        assertEquals("ODP response exceeds its byte limit", failure.getMessage());
    }

    /** ERR-18: nesting is bounded at 16 for a catalog response and at 8 for the Service Document. */
    @Test
    void refusesADocumentNestedPastItsLimit() {
        String tower = "[".repeat(20) + "0" + "]".repeat(20);
        OdpServiceClient client =
                Responses.serving(request -> "{\"odp_version\":\"1.0\",\"items\":[],\"x_tower\":" + tower + "}");
        IllegalStateException listing =
                assertThrows(IllegalStateException.class, () -> client.listOfferings(null, null, null));
        assertEquals("ODP response exceeds its nesting-depth limit", listing.getMessage());

        String deepDocument = Responses.SERVICE_DOCUMENT.strip();
        deepDocument = deepDocument.substring(0, deepDocument.length() - 1) + ",\"x_tower\":" + "[".repeat(10) + "0"
                + "]".repeat(10) + "}";
        String document = deepDocument;
        assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(SERVICE, request -> Responses.ok(request, document)));
    }

    /** MED-08 and MED-09: the media-type essence is compared whole, and case-insensitively. */
    @Test
    void refusesAMediaTypeThatIsNotTheOdpOne() {
        for (String contentType : List.of("application/json", "application/odp+jsonx", "", "application/odp")) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> OdpServiceClient.create(
                            SERVICE,
                            request -> Responses.of(
                                    request,
                                    200,
                                    Responses.SERVICE_DOCUMENT,
                                    Map.of("Content-Type", List.of(contentType)))),
                    contentType);
            assertEquals("ODP response must use application/odp+json", failure.getMessage());
        }
        OdpServiceClient client = OdpServiceClient.create(
                SERVICE,
                request -> Responses.of(
                        request,
                        200,
                        Responses.SERVICE_DOCUMENT,
                        Map.of("Content-Type", List.of("APPLICATION/ODP+JSON; charset=utf-8"))));
        assertEquals("Plant Store", client.inspection().document().name());
    }

    @Test
    void carriesTheHeadersEachRequestNeeds() {
        Responses.Recorder recorder = new Responses.Recorder();
        OdpServiceClient client = OdpServiceClient.create(SERVICE, request -> {
            recorder.record(request);
            return Responses.ok(
                    request,
                    request.uri().getPath().equals("/.well-known/odp") ? Responses.SERVICE_DOCUMENT : EMPTY_PAGE);
        });
        client.listOfferings("full", 25, "fr");
        HttpRequest listing = recorder.last();
        assertEquals(
                "application/odp+json, application/problem+json",
                listing.headers().firstValue("Accept").orElseThrow());
        assertEquals("fr", listing.headers().firstValue("Accept-Language").orElseThrow());
        assertTrue(
                listing.uri().getQuery().contains("representation=full"),
                listing.uri().toString());
        assertTrue(listing.uri().getQuery().contains("limit=25"), listing.uri().toString());

        client.searchOfferings(
                new SearchRequests.Offerings("1.0", "gpu", null, null, null, null, null, null), null, null);
        HttpRequest search = recorder.last();
        assertEquals("POST", search.method());
        assertEquals(Responses.ODP, search.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(search.headers().firstValue("Accept-Language").isEmpty());
    }

    /** ERR-05: a Problem Details document whose status disagrees describes a different failure. */
    @Test
    void readsAProblemOnlyWhenItDescribesTheFailureThatOccurred() {
        String problem = """
                {"code":"NOT_FOUND","status":404,"title":"Absent",
                "type":"https://offeringprotocol.org/problems/not-found"}
                """;
        OdpRequestException matching = assertThrows(
                OdpRequestException.class,
                () -> failing(404, problem, Responses.PROBLEM).listOfferings(null, null, null));
        assertEquals(404, matching.status());
        assertEquals("Absent", matching.getMessage());
        assertNotNull(matching.problem());
        assertNotNull(matching.headers());

        OdpRequestException mismatched = assertThrows(
                OdpRequestException.class,
                () -> failing(503, problem, Responses.PROBLEM).listOfferings(null, null, null));
        assertEquals(503, mismatched.status());
        assertEquals("ODP request failed with HTTP 503", mismatched.getMessage());
        assertNull(mismatched.problem());

        OdpRequestException plain = assertThrows(
                OdpRequestException.class,
                () -> failing(500, "not a problem document", "text/plain").listOfferings(null, null, null));
        assertEquals("ODP request failed with HTTP 500", plain.getMessage());
        assertNull(plain.problem());
    }

    /** ERR-21: a Problem Details response is read under 16,384 bytes, not the catalog budget. */
    @Test
    void refusesAProblemPastItsOwnByteLimit() {
        String problem = "{\"code\":\"NOT_FOUND\",\"status\":404,\"title\":\"Absent\",\"x_pad\":\"" + "t".repeat(20_000)
                + "\",\"type\":\"https://offeringprotocol.org/problems/not-found\"}";
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> failing(404, problem, Responses.PROBLEM).listOfferings(null, null, null));
        assertEquals("ODP response exceeds its byte limit", failure.getMessage());
    }

    @Test
    void followsSameOriginRedirectsAndRefusesTheRest() {
        Responses.Recorder recorder = new Responses.Recorder();
        OdpServiceClient client = OdpServiceClient.create(SERVICE, request -> {
            recorder.record(request);
            if (request.uri().getPath().equals("/.well-known/odp")) {
                return Responses.of(request, 308, "", Map.of("Location", List.of("https://plants.example/moved")));
            }
            return Responses.ok(request, Responses.SERVICE_DOCUMENT);
        });
        assertEquals("Plant Store", client.inspection().document().name());
        assertEquals(2, recorder.requests().size());

        assertEquals(
                "ODP redirect changed Service origin",
                assertThrows(IllegalStateException.class, () -> redirectingTo("https://elsewhere.example/odp"))
                        .getMessage());
        assertEquals(
                "ODP redirect omitted Location",
                assertThrows(IllegalStateException.class, () -> redirectingTo(null))
                        .getMessage());
        assertEquals(
                "ODP response exceeded its redirect limit",
                assertThrows(IllegalStateException.class, () -> redirectingTo("https://plants.example/again"))
                        .getMessage());
    }

    /** A 303, and a 301 or 302 answering a POST, continue as a GET without the original body. */
    @Test
    void rewritesTheMethodEachRedirectStatusRequires() {
        for (int status : List.of(301, 302, 303)) {
            Responses.Recorder recorder = new Responses.Recorder();
            OdpServiceClient client = OdpServiceClient.create(SERVICE, request -> {
                recorder.record(request);
                if (request.uri().getPath().equals("/.well-known/odp")) {
                    return Responses.ok(request, Responses.SERVICE_DOCUMENT);
                }
                if (request.uri().getPath().equals("/odp/offerings/search")) {
                    return Responses.of(
                            request, status, "", Map.of("Location", List.of("https://plants.example/odp/moved")));
                }
                return Responses.ok(request, EMPTY_PAGE);
            });
            client.searchOfferings(
                    new SearchRequests.Offerings("1.0", "gpu", null, null, null, null, null, null), null, null);
            assertEquals("GET", recorder.last().method(), "status " + status);
        }
        Responses.Recorder preserved = new Responses.Recorder();
        OdpServiceClient client = OdpServiceClient.create(SERVICE, request -> {
            preserved.record(request);
            if (request.uri().getPath().equals("/.well-known/odp")) {
                return Responses.ok(request, Responses.SERVICE_DOCUMENT);
            }
            if (request.uri().getPath().equals("/odp/offerings/search")) {
                return Responses.of(request, 307, "", Map.of("Location", List.of("https://plants.example/odp/moved")));
            }
            return Responses.ok(request, EMPTY_PAGE);
        });
        client.searchOfferings(
                new SearchRequests.Offerings("1.0", "gpu", null, null, null, null, null, null), null, null);
        assertEquals("POST", preserved.last().method());
    }

    @Test
    void reportsATransportThatCouldNotAnswer() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(SERVICE, request -> {
                    throw new java.io.IOException("connection reset by peer");
                }));
        assertEquals("ODP request failed", failure.getMessage());

        IllegalStateException interrupted = assertThrows(
                IllegalStateException.class,
                () -> OdpServiceClient.create(SERVICE, request -> {
                    throw new InterruptedException("stopped");
                }));
        assertEquals("ODP request was interrupted", interrupted.getMessage());
        assertTrue(Thread.interrupted());
    }

    private static OdpServiceClient failing(int status, String body, String contentType) {
        return OdpServiceClient.create(
                SERVICE,
                request -> request.uri().getPath().equals("/.well-known/odp")
                        ? Responses.ok(request, Responses.SERVICE_DOCUMENT)
                        : Responses.of(request, status, body, Map.of("Content-Type", List.of(contentType))));
    }

    private static void redirectingTo(String location) {
        OdpServiceClient.create(
                SERVICE,
                request -> Responses.of(
                        request, 302, "", location == null ? Map.of() : Map.of("Location", List.of(location))));
    }

    private static String padded(String json, int bytes) {
        String trimmed = json.strip();
        return trimmed.substring(0, trimmed.length() - 1) + ",\"x_pad\":\"" + "p".repeat(bytes) + "\"}";
    }
}
