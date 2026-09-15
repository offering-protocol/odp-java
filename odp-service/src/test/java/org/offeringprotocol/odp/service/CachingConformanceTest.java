package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.COLLECTIONS;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.with;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpOperation;

class CachingConformanceTest {
    private final OdpService service = Catalog.service();

    /** PAG-31: a GET carries a validator, so an Agent can ask whether anything changed. */
    @Test
    void offersAValidatorOnEveryRetrieval() {
        for (String path : List.of(Odp.SERVICE_DOCUMENT_PATH, OFFERINGS, OFFERINGS + "/plant-1", COLLECTIONS)) {
            String tag = service.handle(get(path)).headers().get("ETag");
            assertTrue(tag != null && tag.startsWith("\"") && tag.endsWith("\""), path + " -> " + tag);
            assertEquals(tag, service.handle(get(path)).headers().get("ETag"), "the same document, the same tag");
        }
    }

    @Test
    void answersAConditionalRetrievalOfAnUnchangedResourceWithNotModified() {
        String tag = service.handle(get(OFFERINGS)).headers().get("ETag");
        OdpHttpResponse response = service.handle(with(OFFERINGS, "If-None-Match", tag));

        assertEquals(304, response.status());
        assertEquals("", response.body());
        assertEquals(tag, response.headers().get("ETag"));
        assertNull(response.headers().get("Content-Type"), "a 304 carries no representation to describe");
        assertEquals("Accept-Language", response.headers().get("Vary"));
    }

    @Test
    void readsEveryFormOfTheConditionalField() {
        String tag = service.handle(get(OFFERINGS)).headers().get("ETag");

        assertEquals(304, service.handle(with(OFFERINGS, "If-None-Match", "*")).status());
        assertEquals(
                304,
                service.handle(with(OFFERINGS, "If-None-Match", "W/" + tag)).status());
        assertEquals(
                304,
                service.handle(with(OFFERINGS, "If-None-Match", "\"other\", " + tag))
                        .status());
        assertEquals(
                200,
                service.handle(with(OFFERINGS, "If-None-Match", "\"other\"")).status());
        assertEquals(200, service.handle(with(OFFERINGS, "If-None-Match", "")).status());
    }

    @Test
    void answersAFailedPreconditionWithoutTheResource() {
        String tag = service.handle(get(OFFERINGS)).headers().get("ETag");

        assertEquals(200, service.handle(with(OFFERINGS, "If-Match", tag)).status());
        assertEquals(200, service.handle(with(OFFERINGS, "If-Match", "*")).status());

        OdpHttpResponse response = service.handle(with(OFFERINGS, "If-Match", "\"stale\""));
        assertEquals(412, response.status());
        assertTrue(response.body().contains("PRECONDITION_FAILED"));
        assertTrue(!response.body().contains("plant-1"));
    }

    /** SVC-61: two variants of one resource never share a validator. */
    @Test
    void givesEachVariantItsOwnValidator() {
        OdpService acting = acting();
        String terse = acting.handle(get(OFFERINGS + "/plant-1")).headers().get("ETag");
        String full = acting.handle(get(OFFERINGS + "/plant-1", Catalog.query("representation", "full")))
                .headers()
                .get("ETag");
        assertNotEquals(terse, full);

        OdpService multilingual = Catalog.multilingual("en", "fr");
        assertNotEquals(
                multilingual
                        .handle(with(OFFERINGS, "Accept-Language", "en"))
                        .headers()
                        .get("ETag"),
                multilingual
                        .handle(with(OFFERINGS, "Accept-Language", "fr"))
                        .headers()
                        .get("ETag"));
    }

    /** A conditional retrieval of one variant does not answer for another. */
    @Test
    void doesNotAnswerOneVariantWithAnothersValidator() {
        OdpService acting = acting();
        String terse = acting.handle(get(OFFERINGS + "/plant-1")).headers().get("ETag");
        OdpHttpResponse response = acting.handle(new OdpHttpRequest(
                "GET",
                OFFERINGS + "/plant-1",
                Catalog.query("representation", "full"),
                Map.of("If-None-Match", List.of(terse)),
                null));

        assertEquals(200, response.status());
    }

    /** CCH-01: each resource class states its own freshness rather than leaving a cache to guess. */
    @ParameterizedTest
    @CsvSource({
        "/.well-known/odp,public max-age=14400",
        "/odp/offerings,public max-age=300",
        "/odp/offerings/plant-1,public max-age=300",
        "/odp/collections,public max-age=3600",
        "/odp/collections/plants,public max-age=3600",
        "/odp/collections/plants/offerings,public max-age=300"
    })
    void statesTheFreshnessOfEachResourceClass(String path, String expected) {
        assertEquals(
                expected.replace(' ', ','),
                service.handle(get(path)).headers().get("Cache-Control").replace(", ", ","));
    }

    /** A search answers the request that was made, and is never reused for the next one. */
    @Test
    void keepsSearchResponsesOutOfCaches() {
        OdpService searching = Catalog.handling(request -> Catalog.page(List.of(), null));

        assertEquals(
                "no-store",
                searching.handle(get(OFFERINGS + "/search")).headers().get("Cache-Control"));
    }

    /** CCH-05/06: a response an Agent had to authenticate for is not a shared one. */
    @Test
    void marksAnAuthenticatedResponsePrivate() {
        OdpService guarded = OdpService.builder("Plant Store", "Plants for agents.", "en", Catalog.BASE)
                .endpoints(StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()))
                .protocols(new org.offeringprotocol.odp.core.ServiceDocument.Protocols(
                        List.of(new org.offeringprotocol.odp.core.ServiceDocument.EnrollmentProtocol("aep")), null))
                .operationAuthentication(Map.of(OdpOperation.GET_OFFERING, AuthenticationRequirement.REQUIRED))
                .build();

        assertTrue(guarded.handle(get(OFFERINGS + "/plant-1"))
                .headers()
                .get("Cache-Control")
                .startsWith("private"));
        assertTrue(guarded.handle(get(OFFERINGS)).headers().get("Cache-Control").startsWith("public"));
    }

    /** An Offering whose Full representation says more than its Terse one. */
    private static OdpService acting() {
        return new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "Rubber Plant", List.of(Catalog.action()), null)),
                        List.of()));
    }

    /** A problem describes this request only, so nothing may store it and answer the next one. */
    @Test
    void keepsProblemsOutOfCaches() {
        OdpHttpResponse response = service.handle(get(OFFERINGS + "/absent"));

        assertEquals("no-store", response.headers().get("Cache-Control"));
        assertEquals("application/problem+json", response.headers().get("Content-Type"));
    }
}
