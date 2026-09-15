package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.BASE;
import static org.offeringprotocol.odp.service.Catalog.COLLECTIONS;
import static org.offeringprotocol.odp.service.Catalog.ODP_JSON;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.post;
import static org.offeringprotocol.odp.service.Catalog.query;
import static org.offeringprotocol.odp.service.Catalog.with;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.offeringprotocol.odp.core.Odp;

class TransportConformanceTest {
    private final OdpService service = Catalog.service();

    /** MED-07: a successful ODP response says what it is carrying. */
    @Test
    void servesEveryDocumentAsOdpJson() {
        for (String path : List.of(Odp.SERVICE_DOCUMENT_PATH, OFFERINGS, OFFERINGS + "/plant-1", COLLECTIONS)) {
            OdpHttpResponse response = service.handle(get(path));
            assertEquals(200, response.status(), path);
            assertEquals(ODP_JSON, response.headers().get("Content-Type"), path);
        }
    }

    /** MED-03: an absent field, a wildcard, and a range that covers the type all take ODP. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "*/*",
                "application/*",
                ODP_JSON,
                "APPLICATION/ODP+JSON",
                "text/html, application/odp+json;q=0.9",
                "text/html;q=0.9, */*;q=0.1"
            })
    void servesARequestWhoseAcceptCoversOdp(String accept) {
        assertEquals(200, service.handle(with(OFFERINGS, "Accept", accept)).status());
    }

    /** MED-04: a field that leaves the ODP media type out, or scores it zero, is a refusal. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "text/html",
                "application/json",
                "*/*;q=0",
                "application/odp+json;q=0",
                "*/*, application/odp+json;q=0",
                "application/odp+json;q=nonsense"
            })
    void refusesARequestThatWillNotTakeOdp(String accept) {
        OdpHttpResponse response = service.handle(with(OFFERINGS, "Accept", accept));

        assertEquals(406, response.status(), accept);
        assertTrue(response.body().contains("NOT_ACCEPTABLE"));
    }

    /** A more specific range settles the question, even when a broader one scores ODP at zero. */
    @Test
    void readsTheMostSpecificRangeThatCoversTheType() {
        assertEquals(
                200,
                service.handle(with(OFFERINGS, "Accept", "*/*;q=0, application/odp+json"))
                        .status());
    }

    /** MED-06: a body carried into an ODP operation is an ODP document or it is not read. */
    @Test
    void refusesARequestBodyThatIsNotOdpJson() {
        OdpService searching = Catalog.handling(request -> Catalog.page(List.of(), null));

        assertEquals(
                415, searching.handle(post(OFFERINGS + "/search", null, "{}")).status());
        assertEquals(
                415,
                searching
                        .handle(post(OFFERINGS + "/search", "application/json", "{}"))
                        .status());
        assertEquals(
                200,
                searching
                        .handle(post(OFFERINGS + "/search", "application/odp+json; charset=utf-8", "{}"))
                        .status());
        assertEquals(
                200,
                searching.handle(post(OFFERINGS + "/search", null, "")).status(),
                "a request with no body declares no media type");
    }

    /** SVC-73: a value this Service cannot act on is refused, repeated or simply unsupported. */
    @Test
    void refusesAQueryValueItCannotActOn() {
        assertEquals(
                400,
                service.handle(get(OFFERINGS, query("representation", "brief"))).status());
        assertEquals(
                400,
                service.handle(get(OFFERINGS, query("representation", "terse", "full")))
                        .status());
        assertEquals(400, service.handle(get(OFFERINGS, query("limit", "0"))).status());
        assertEquals(400, service.handle(get(OFFERINGS, query("limit", "101"))).status());
        assertEquals(
                400, service.handle(get(OFFERINGS, query("limit", "plenty"))).status());
        assertEquals(
                400, service.handle(get(OFFERINGS, query("limit", "2", "3"))).status());
        assertEquals(
                400, service.handle(get(OFFERINGS, query("cursor", "a", "b"))).status());
        assertEquals(200, service.handle(get(OFFERINGS, query("limit", "100"))).status());
    }

    /** A path this Service publishes but by another method says which methods it does publish. */
    @Test
    void namesTheMethodsAResourceAllows() {
        OdpHttpResponse response = service.handle(post(OFFERINGS, ODP_JSON, null));

        assertEquals(405, response.status());
        assertEquals("GET, HEAD", response.headers().get("Allow"));
        assertTrue(response.body().contains("METHOD_NOT_ALLOWED"));

        OdpService searching = Catalog.handling(request -> Catalog.page(List.of(), null));
        assertEquals(
                "GET, HEAD, POST",
                searching
                        .handle(new OdpHttpRequest("DELETE", OFFERINGS + "/search", Map.of(), Map.of(), null))
                        .headers()
                        .get("Allow"));
        assertEquals(
                "GET, HEAD",
                service.handle(new OdpHttpRequest("PUT", Odp.SERVICE_DOCUMENT_PATH, Map.of(), Map.of(), null))
                        .headers()
                        .get("Allow"));
    }

    /** RFC 9110 9.3.2: a HEAD is a GET whose body is left out, with the same fields. */
    @Test
    void answersHeadWithTheFieldsOfTheGetItStandsFor() {
        OdpHttpResponse body = service.handle(get(Odp.SERVICE_DOCUMENT_PATH));
        OdpHttpResponse head =
                service.handle(new OdpHttpRequest("HEAD", Odp.SERVICE_DOCUMENT_PATH, Map.of(), Map.of(), null));

        assertEquals(200, head.status());
        assertEquals("", head.body());
        assertEquals(body.headers().get("ETag"), head.headers().get("ETag"));
        assertEquals(ODP_JSON, head.headers().get("Content-Type"));
        assertEquals(
                Integer.toString(body.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                head.headers().get("Content-Length"));
    }

    /** ERR-31: a request past the byte limit is refused before anything reads it. */
    @Test
    void refusesARequestPastItsByteLimit() {
        OdpService searching = Catalog.handling(request -> Catalog.page(List.of(), null));

        assertEquals(
                200,
                searching
                        .handle(post(OFFERINGS + "/search", ODP_JSON, "{\"query\":\"" + "a".repeat(65_500) + "\"}"))
                        .status());
        OdpHttpResponse refused = searching.handle(post(OFFERINGS + "/search", ODP_JSON, "a".repeat(65_537)));
        assertEquals(413, refused.status());
        assertTrue(refused.body().contains("REQUEST_TOO_LARGE"));
    }

    // -- routing ----------------------------------------------------------------------------

    @Test
    void routesEveryOperationItPublishes() {
        assertEquals(200, service.handle(get(Odp.SERVICE_DOCUMENT_PATH)).status());
        assertEquals(200, service.handle(get(OFFERINGS)).status());
        assertEquals(200, service.handle(get(OFFERINGS + "/plant-1")).status());
        assertEquals(200, service.handle(get(COLLECTIONS)).status());
        assertEquals(200, service.handle(get(COLLECTIONS + "/plants")).status());
        assertEquals(200, service.handle(get(COLLECTIONS + "/plants/offerings")).status());
    }

    /** A path that is not a resource of this Service is not a resource of this Service. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/offerings",
                "/odpx/offerings",
                BASE,
                BASE + "/",
                OFFERINGS + "/plant-1/",
                OFFERINGS + "/plant-1/actions",
                COLLECTIONS + "//offerings",
                COLLECTIONS + "/plants/offerings/extra",
                COLLECTIONS + "/plants/collections",
                "/.well-known/odp/",
                "/.well-known/other"
            })
    void refusesAPathItDoesNotPublish(String path) {
        assertEquals(404, service.handle(get(path)).status(), path);
    }

    /** IDN-08: a segment standing where an identifier belongs is an identifier or it names nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"a?b", "a b", "a/b", ".", "..", "pl%61nt", "plant!"})
    void refusesAPathSegmentThatIsNotAnIdentifier(String identifier) {
        assertEquals(404, service.handle(get(OFFERINGS + "/" + identifier)).status(), identifier);
    }

    @Test
    void refusesAnIdentifierLongerThanAnIdentifierCanBe() {
        assertEquals(404, service.handle(get(OFFERINGS + "/" + "a".repeat(129))).status());
        assertEquals(
                404, service.handle(get(OFFERINGS + "/" + "a".repeat(128))).status(), "in range, but no such Offering");
    }

    @Test
    void readsAnIdentifierOffThePathAndHandsItToTheHandler() {
        OdpService echoing = Catalog.handling(request -> Catalog.offering(request.identifier(), "Echoed"));

        assertTrue(echoing.handle(get(OFFERINGS + "/plant.9_x~y-z")).body().contains("plant.9_x~y-z"));
    }

    /** A route whose operation this Service does not publish is absent, not refused by method. */
    @Test
    void refusesAnOperationItDoesNotPublish() {
        OdpService withoutCollections = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()));

        assertEquals(404, withoutCollections.handle(get(COLLECTIONS)).status());
        assertEquals(
                404, withoutCollections.handle(get(COLLECTIONS + "/plants")).status());
        assertEquals(404, withoutCollections.handle(get(OFFERINGS + "/search")).status());
    }

    /** An endpoint base of "/" publishes the operations at the root. */
    @Test
    void servesFromTheRootWhenThatIsTheEndpointBase() {
        OdpService rooted = OdpService.builder("Plant Store", "Plants for agents.", "en", "/")
                .endpoints(StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                .build();

        assertEquals(200, rooted.handle(get("/offerings")).status());
        assertEquals(200, rooted.handle(get("/offerings/plant-1")).status());
        assertEquals(200, rooted.handle(get(Odp.SERVICE_DOCUMENT_PATH)).status());
    }

    @Test
    void answersAResourceTheCatalogDoesNotHaveWithNotFound() {
        OdpHttpResponse response = service.handle(get(OFFERINGS + "/absent"));

        assertEquals(404, response.status());
        assertTrue(response.body().contains("ODP resource not found"));
        assertNull(response.headers().get("ETag"));
        assertFalse(response.body().contains("plant-1"));
    }
}
