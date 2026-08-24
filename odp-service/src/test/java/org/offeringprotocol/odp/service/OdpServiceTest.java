package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.Offering;
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
        OdpService service = new OdpService(template(), StaticCatalog.create(List.of(offering), List.of()));

        OdpHttpResponse document = service.handle(request("GET", "/.well-known/odp", Map.of()));
        OdpHttpResponse list =
                service.handle(request("GET", "/odp/offerings", Map.of("representation", List.of("full"))));
        OdpHttpResponse detail = service.handle(request("GET", "/odp/offerings/plant-1", Map.of()));

        assertEquals(200, document.status());
        assertTrue(document.body().contains("list-offerings"));
        assertTrue(list.body().contains("Rubber Plant"));
        assertTrue(detail.body().contains("plant-1"));
    }

    private static ServiceDocument template() {
        return new ServiceDocument(
                Odp.VERSION,
                "Plant Store",
                "Plants for agents.",
                null,
                "en",
                List.of("en"),
                null,
                List.of("plants"),
                null,
                List.of(),
                new ServiceDocument.Http("/odp", null),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private static OdpHttpRequest request(String method, String path, Map<String, List<String>> query) {
        return new OdpHttpRequest(method, path, query, Map.of(), null);
    }
}
