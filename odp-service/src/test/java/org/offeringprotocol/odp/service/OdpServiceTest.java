package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ServiceDocument;

class OdpServiceTest {
    @Test
    void servesTheMinimumStaticCatalog() {
        Offering offering = new Offering(
                null,
                Odp.VERSION,
                "plant-1",
                "Rubber Plant",
                "A resilient houseplant.",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        OdpService service = OdpService.builder("Plant Store", "Plants for agents.", "en", "/odp")
                .keywords(List.of("plants"))
                .endpoints(StaticCatalog.create(List.of(offering), List.of()))
                .protocols(new ServiceDocument.Protocols(List.of(new ServiceDocument.EnrollmentProtocol("aep")), null))
                .operationAuthentication(Map.of(OdpOperation.GET_OFFERING, AuthenticationRequirement.REQUIRED))
                .build();

        OdpHttpResponse document = service.handle(request("GET", "/.well-known/odp", Map.of()));
        OdpHttpResponse list =
                service.handle(request("GET", "/odp/offerings", Map.of("representation", List.of("full"))));
        OdpHttpResponse detail = service.handle(request("GET", "/odp/offerings/plant-1", Map.of()));

        assertEquals(200, document.status());
        assertTrue(document.body().contains("list-offerings"));
        assertTrue(document.body().contains("\"authentication\":\"required\""));
        assertTrue(list.body().contains("Rubber Plant"));
        assertTrue(detail.body().contains("plant-1"));
    }

    @Test
    void builderRequiresEndpoints() {
        IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> OdpService.builder("Plant Store", "Plants for agents.", "en", "/odp")
                        .build());
        assertEquals("endpoints must be configured", exception.getMessage());
    }

    @Test
    void boundsRequestBodies() {
        Offering offering = new Offering(
                null,
                Odp.VERSION,
                "plant-1",
                "Rubber Plant",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
        OdpService service = new OdpService(template(), StaticCatalog.create(List.of(offering), List.of()));

        OdpHttpResponse boundary =
                service.handle(new OdpHttpRequest("GET", "/.well-known/odp", Map.of(), Map.of(), "a".repeat(65_536)));
        OdpHttpResponse exceeded =
                service.handle(new OdpHttpRequest("GET", "/.well-known/odp", Map.of(), Map.of(), "a".repeat(65_537)));

        assertEquals(200, boundary.status());
        assertEquals(413, exceeded.status());
        assertTrue(exceeded.body().contains("REQUEST_TOO_LARGE"));
    }

    @Test
    void staticCatalogSnapshotsCallerCollections() {
        Offering original = offering("plant-1", "Rubber Plant");
        List<Offering> offerings = new ArrayList<>(List.of(original));
        OdpService service = new OdpService(template(), StaticCatalog.create(offerings, List.of()));

        offerings.add(offering("plant-2", "Snake Plant"));

        OdpHttpResponse response =
                service.handle(request("GET", "/odp/offerings", Map.of("representation", List.of("full"))));
        assertTrue(response.body().contains("Rubber Plant"));
        assertFalse(response.body().contains("Snake Plant"));
    }

    @Test
    void routesSearchPostAndContinuationGetToTheSameHandler() {
        Map<OdpOperation, OdpService.Endpoint> endpoints =
                new java.util.EnumMap<>(StaticCatalog.create(List.of(offering("plant-1", "Rubber Plant")), List.of()));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
                    String name = request.cursor() == null ? "Initial result" : "Continued result";
                    return new Page<>(null, Odp.VERSION, List.of(offering("result", name)), null, Map.of());
                }));
        OdpService service = new OdpService(template(), endpoints);

        OdpHttpResponse initial = service.handle(request("POST", "/odp/offerings/search", Map.of()));
        OdpHttpResponse continuation =
                service.handle(request("GET", "/odp/offerings/search", Map.of("cursor", List.of("opaque"))));

        assertTrue(initial.body().contains("Initial result"));
        assertTrue(continuation.body().contains("Continued result"));
    }

    private static Offering offering(String id, String name) {
        return new Offering(
                null,
                Odp.VERSION,
                id,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private static ServiceDocument template() {
        return ServiceDocument.builder(
                        "Plant Store", "Plants for agents.", "en", new ServiceDocument.Http("/odp", null))
                .keywords(List.of("plants"))
                .operations(List.of())
                .build();
    }

    private static OdpHttpRequest request(String method, String path, Map<String, List<String>> query) {
        return new OdpHttpRequest(method, path, query, Map.of(), null);
    }
}
