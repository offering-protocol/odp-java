package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.get;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.SearchCapabilities;
import org.offeringprotocol.odp.core.ServiceDocument;

class ServiceDocumentTest {
    /** SVC-82: a Service that cannot list and retrieve Offerings is not a Service. */
    @Test
    void refusesToStandUpWithoutTheBaselineOperations() {
        assertEquals(
                "ODP Services require list-offerings and get-offering handlers",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new OdpService(Catalog.template(List.of("en")), Map.of()))
                        .getMessage());
        assertThrows(NullPointerException.class, () -> new OdpService(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new OdpService(Catalog.template(List.of("en")), null));
        assertEquals(
                "endpoints must be configured",
                assertThrows(
                                IllegalStateException.class,
                                () -> OdpService.builder("Plant Store", "Plants.", "en", "/odp")
                                        .build())
                        .getMessage());
    }

    /** ROLE-04/SVC-77: the advertised operations are exactly the handlers that were registered. */
    @Test
    void advertisesExactlyTheOperationsItImplements() {
        ServiceDocument document = Catalog.service().document();

        assertEquals(
                List.of(
                        OdpOperation.GET_COLLECTION,
                        OdpOperation.GET_OFFERING,
                        OdpOperation.LIST_COLLECTION_OFFERINGS,
                        OdpOperation.LIST_COLLECTIONS,
                        OdpOperation.LIST_OFFERINGS),
                document.operations().stream()
                        .map(org.offeringprotocol.odp.core.OperationDescriptor::name)
                        .toList());
        assertEquals(Odp.VERSION, document.odpVersion());
    }

    /** SVC-80: an operation the Agent must authenticate for needs an enrollment protocol to do it. */
    @Test
    void carriesThePerOperationAuthenticationItWasGiven() {
        OdpService service = OdpService.builder("Plant Store", "Plants for agents.", "en", "/odp")
                .endpoints(StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                .protocols(new ServiceDocument.Protocols(List.of(new ServiceDocument.EnrollmentProtocol("aep")), null))
                .operationAuthentication(Map.of(OdpOperation.GET_OFFERING, AuthenticationRequirement.REQUIRED))
                .build();

        assertTrue(service.handle(get(Odp.SERVICE_DOCUMENT_PATH)).body().contains("\"authentication\":\"required\""));
    }

    @Test
    void refusesAnAuthenticationRuleForAnOperationItDoesNotPublish() {
        assertEquals(
                "authentication requirement refers to an unconfigured operation: search-offerings",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> OdpService.builder("Plant Store", "Plants.", "en", "/odp")
                                        .endpoints(StaticCatalog.create(
                                                List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                                        .operationAuthentication(Map.of(
                                                OdpOperation.SEARCH_OFFERINGS, AuthenticationRequirement.REQUIRED))
                                        .build())
                        .getMessage());
    }

    /** SVC-83/84: the Service Document this builder produces is one the protocol would accept. */
    @Test
    void producesADocumentTheProtocolAccepts() {
        OdpService service = OdpService.builder("Plant Store", "Plants for agents.", "en", "/odp/")
                .documentationUrl("https://plants.example/docs")
                .localizations(List.of("en", "fr"))
                .keywords(List.of("plants", "houseplants"))
                .branding(new ServiceDocument.Branding(
                        new ServiceDocument.BrandingImage("https://plants.example/icon.png", "image/png"),
                        new ServiceDocument.BrandingImage("https://plants.example/logo.png", "image/png")))
                .mcp(List.of(new ServiceDocument.McpEndpoint(
                        null, "Plants", "streamable-http", "https://plants.example/mcp")))
                .openApi(new ServiceDocument.OpenApi("https://plants.example/openapi.json"))
                .paymentOrigins(List.of("https://pay.example"))
                .protocols(new ServiceDocument.Protocols(List.of(new ServiceDocument.EnrollmentProtocol("aep")), null))
                .statusUrl("https://plants.example/status")
                .supportUrl("https://plants.example/support")
                .websiteUrl("https://plants.example")
                .additional(Map.of("x_region", OdpJson.parseTree("\"eu\"")))
                .endpoints(StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                .build();

        String body = service.handle(get(Odp.SERVICE_DOCUMENT_PATH)).body();
        ServiceDocument parsed = OdpJson.parseServiceDocument(body);

        assertEquals("Plant Store", parsed.name());
        assertEquals("/odp/", parsed.http().endpointBase());
        assertEquals(List.of("en", "fr"), parsed.localizations());
        assertEquals("eu", parsed.additional().get("x_region").asString());
        assertNull(parsed.searchCapabilities());
        assertEquals(200, service.handle(get("/odp/offerings")).status(), "a trailing slash in the base is trimmed");
    }

    /** FLT-49: search capabilities belong to a Service that advertises the search operation. */
    @Test
    void refusesSearchCapabilitiesWithoutTheSearchOperation() {
        assertThrows(
                RuntimeException.class,
                () -> OdpService.builder("Plant Store", "Plants.", "en", "/odp")
                        .searchCapabilities(new SearchCapabilities(null, null, Map.of()))
                        .endpoints(
                                StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                        .build());
    }

    /** A caller cannot reach into the document this Service serves. */
    @Test
    void handsOutACopyOfItsDocument() {
        OdpService service = Catalog.service();

        assertNotSame(service.document(), service.document());
        assertEquals(service.document(), service.document());
    }

    @Test
    void refusesARequestItWasNotGiven() {
        assertThrows(NullPointerException.class, () -> Catalog.service().handle(null));
    }

    /** SVC-02: the Service Document is served whatever the catalog operations require. */
    @Test
    void servesTheDocumentWithoutTheAccessPolicyOfItsOperations() {
        OdpService guarded = OdpService.builder("Plant Store", "Plants for agents.", "en", "/odp")
                .endpoints(StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                .protocols(new ServiceDocument.Protocols(List.of(new ServiceDocument.EnrollmentProtocol("aep")), null))
                .operationAuthentication(Map.of(
                        OdpOperation.GET_OFFERING,
                        AuthenticationRequirement.REQUIRED,
                        OdpOperation.LIST_OFFERINGS,
                        AuthenticationRequirement.REQUIRED))
                .build();

        assertEquals(200, guarded.handle(get(Odp.SERVICE_DOCUMENT_PATH)).status());
    }

    /** An endpoint is a requirement and a handler, and neither of them is optional. */
    @Test
    void refusesAnIncompleteEndpoint() {
        assertThrows(
                NullPointerException.class,
                () -> new OdpService.Endpoint(null, request -> Catalog.offering("plant-1", "Rubber Plant")));
        assertThrows(
                NullPointerException.class,
                () -> new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, null));
    }

    /** A request record is a snapshot of what arrived, not a window onto the caller's maps. */
    @Test
    void snapshotsTheRequestItWasGiven() {
        Map<String, List<String>> query = new java.util.HashMap<>(Map.of("limit", List.of("2")));
        OdpHttpRequest request = new OdpHttpRequest("GET", "/odp/offerings", query, null, null);
        query.put("cursor", List.of("c1"));

        assertEquals(Map.of("limit", List.of("2")), request.query());
        assertEquals(Map.of(), request.headers());
        assertEquals("2", request.queryValue("limit"));
        assertNull(request.queryValue("cursor"));
        assertNull(request.headerValue("Accept"));
        assertThrows(UnsupportedOperationException.class, () -> request.query().clear());
    }

    @Test
    void readsAHeaderWithoutRegardToCase() {
        OdpHttpRequest request =
                new OdpHttpRequest("GET", "/odp/offerings", Map.of(), Map.of("aCCePt-LaNguaGe", List.of("fr")), null);

        assertEquals("fr", request.headerValue("Accept-Language"));
        assertEquals("fr", request.headerValue("ACCEPT-LANGUAGE"));
    }

    @Test
    void readsAResponseWithNoHeadersOrBodyAsAnEmptyOne() {
        OdpHttpResponse response = new OdpHttpResponse(204, null, null);

        assertEquals(Map.of(), response.headers());
        assertEquals("", response.body());
    }
}
