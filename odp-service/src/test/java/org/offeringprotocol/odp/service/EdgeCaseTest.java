package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.COLLECTIONS;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.query;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.ResourceImage;
import org.offeringprotocol.odp.core.ServiceDocument;

class EdgeCaseTest {
    /** A Service Document with no localizations of its own still answers in the one language it has. */
    @Test
    void answersInItsOnlyLanguageWhenItListsNoLocalizations() {
        ServiceDocument template = ServiceDocument.builder(
                        "Plant Store", "Plants for agents.", "en", new ServiceDocument.Http(Catalog.BASE, null))
                .operations(List.of())
                .build();
        OdpService service = new OdpService(
                template, StaticCatalog.create(List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of()));

        assertEquals(
                "en",
                service.handle(Catalog.with(OFFERINGS, "Accept-Language", "fr"))
                        .headers()
                        .get("Content-Language"));
    }

    /** A field carrying parameters that are not a quality is still a range at full quality. */
    @Test
    void readsARangeWhoseParametersAreNotQualities() {
        assertEquals(
                200,
                Catalog.service()
                        .handle(Catalog.with(OFFERINGS, "Accept", "application/odp+json;profile=x;charset=utf-8"))
                        .status());
    }

    /** A request stuffed with header entries is read up to a bound and no further. */
    @Test
    void readsOnlyAsManyHeaderEntriesAsItWillConsider() {
        List<String> many = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            many.add("lang-" + index);
        }
        many.add("fr");
        OdpService service = Catalog.multilingual("en", "fr");

        assertEquals(
                "en",
                service.handle(new OdpHttpRequest("GET", OFFERINGS, Map.of(), Map.of("Accept-Language", many), null))
                        .headers()
                        .get("Content-Language"),
                "the hundredth range is past what any real request states");
        assertEquals(
                "en",
                service.handle(Catalog.with(OFFERINGS, "Accept-Language", String.join(",", many)))
                        .headers()
                        .get("Content-Language"));
    }

    /** REP-27: a Terse Representation carries the primary image only. */
    @Test
    void carriesOnlyThePrimaryImageInATerseRepresentation() {
        Offering illustrated = illustrated("plant-1");
        Collection pictured = pictured("plants");
        Map<OdpOperation, OdpService.Endpoint> endpoints =
                new EnumMap<>(StaticCatalog.create(List.of(illustrated), List.of(pictured)));
        OdpService service = new OdpService(Catalog.template(List.of("en")), endpoints);

        assertFalse(service.handle(get(OFFERINGS + "/plant-1")).body().contains("second.png"));
        assertTrue(service.handle(get(OFFERINGS + "/plant-1", query("representation", "full")))
                .body()
                .contains("second.png"));
        assertFalse(service.handle(get(COLLECTIONS + "/plants")).body().contains("second.png"));
        assertTrue(service.handle(get(COLLECTIONS + "/plants", query("representation", "full")))
                .body()
                .contains("second.png"));
        assertFalse(service.handle(get(COLLECTIONS)).body().contains("second.png"));
    }

    /** A Full page of Collections carries the complete records, without restating the version. */
    @Test
    void servesAFullPageOfCollections() {
        OdpService service = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of(pictured("plants"))));

        OdpHttpResponse response = service.handle(get(COLLECTIONS, query("representation", "full")));
        assertEquals(200, response.status());
        assertTrue(response.body().contains("second.png"));
        assertEquals(1, occurrences(response.body(), "\"odp_version\""));
    }

    /** A search over Collections is validated as a page of Collections. */
    @Test
    void validatesASearchOverCollections() {
        Map<OdpOperation, OdpService.Endpoint> endpoints = new EnumMap<>(StaticCatalog.create(
                List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of(Catalog.collection("plants"))));
        endpoints.put(
                OdpOperation.SEARCH_COLLECTIONS,
                new OdpService.Endpoint(
                        AuthenticationRequirement.NOT_REQUIRED,
                        request -> new org.offeringprotocol.odp.core.Page<>(
                                null,
                                Odp.VERSION,
                                List.of(request.cursor() == null ? embedded("plants") : Catalog.collection("plants")),
                                null,
                                Map.of())));
        OdpService service = new OdpService(Catalog.template(List.of("en")), endpoints);

        assertEquals(200, service.handle(get(COLLECTIONS + "/search")).status());
        assertEquals(
                500,
                service.handle(get(COLLECTIONS + "/search", query("cursor", "c1")))
                        .status(),
                "a Collection item that restates the version is not one this Service sends");
    }

    /** ERR-18: nesting is measured from the top-level value, and a brace inside a string is text. */
    @Test
    void measuresNestingWithoutCountingBracesInsideStrings() {
        OdpService service = Catalog.handling(request -> Catalog.offering("plant-1", "{[\\\"] not nesting"));

        assertEquals(200, service.handle(get(OFFERINGS + "/plant-1")).status());
    }

    /** A continuation with no query, and one whose query names something else, both read as absent. */
    @Test
    void readsAContinuationThatNamesNoCursor() {
        assertEquals(
                200,
                Catalog.handling(request -> Catalog.page(List.of(), "/odp/offerings"))
                        .handle(get(OFFERINGS, query("cursor", "c1")))
                        .status());
        assertEquals(
                200,
                Catalog.handling(request -> Catalog.page(List.of(), "/odp/offerings?limit=2&=empty"))
                        .handle(get(OFFERINGS, query("cursor", "c1")))
                        .status());
    }

    /** A failure with nothing to say carries no detail rather than the word null. */
    @Test
    void publishesNoDetailWhenAFailureHasNoMessage() {
        OdpHttpResponse response = Catalog.failing(new OdpServiceException(400, "INVALID_REQUEST", null))
                .handle(get(OFFERINGS + "/plant-1"));

        assertEquals(400, response.status());
        assertNull(OdpJson.read(response.body(), org.offeringprotocol.odp.core.ProblemDetails.class)
                .detail());
        assertFalse(response.body().contains("null"));
    }

    @Test
    void replacesACodeThatIsNotThereAtAll() {
        assertEquals(
                "INVALID_REQUEST",
                OdpJson.read(
                                Catalog.failing(new OdpServiceException(400, null, "nope"))
                                        .handle(get(OFFERINGS + "/plant-1"))
                                        .body(),
                                org.offeringprotocol.odp.core.ProblemDetails.class)
                        .code());
    }

    /** A range less specific than one already read does not unseat it. */
    @Test
    void keepsTheMoreSpecificRangeItAlreadyRead() {
        assertEquals(
                200,
                Catalog.service()
                        .handle(Catalog.with(OFFERINGS, "Accept", "application/odp+json, */*;q=0.1"))
                        .status());
    }

    /** Every range a request states can be one it ruled out, and the default still answers. */
    @Test
    void answersWhenEveryLanguageRangeWasRuledOut() {
        assertEquals(
                "en",
                Catalog.multilingual("en", "fr")
                        .handle(Catalog.with(OFFERINGS, "Accept-Language", "fr;q=0"))
                        .headers()
                        .get("Content-Language"));
    }

    /** A range that is nothing but parameters names no language at all. */
    @Test
    void ignoresARangeThatNamesNoLanguage() {
        assertEquals(
                "en",
                Catalog.multilingual("en", "fr")
                        .handle(Catalog.with(OFFERINGS, "Accept-Language", " ;q=0.5, x"))
                        .headers()
                        .get("Content-Language"));
    }

    /** Lookup truncates to nothing rather than looping when a range begins with a separator. */
    @Test
    void stopsTruncatingWhenNothingIsLeftOfTheRange() {
        OdpService service = Catalog.multilingual("en", "fr");

        assertEquals(
                "en",
                service.handle(Catalog.with(OFFERINGS, "Accept-Language", "-fr"))
                        .headers()
                        .get("Content-Language"));
        assertEquals(
                "en",
                service.handle(Catalog.with(OFFERINGS, "Accept-Language", "x-foo"))
                        .headers()
                        .get("Content-Language"));
    }

    /** A Collection path segment is held to the same identifier syntax as an Offering's. */
    @Test
    void refusesACollectionSegmentThatIsNotAnIdentifier() {
        assertEquals(404, Catalog.service().handle(get(COLLECTIONS + "/a b")).status());
        assertEquals(
                404,
                Catalog.service().handle(get(COLLECTIONS + "/a b/offerings")).status());
    }

    /** A Collection may be called "search"; only the search path itself is reserved. */
    @Test
    void readsSearchAsACollectionNameWhereAnIdentifierBelongs() {
        OdpService service = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of(Catalog.collection("search"))));

        assertEquals(200, service.handle(get(COLLECTIONS + "/search/offerings")).status());
    }

    /** SEARCH_OFFERINGS results are validated as Offerings like any other page. */
    @Test
    void validatesTheOfferingsASearchReturned() {
        OdpService searching =
                Catalog.handling(request -> Catalog.page(List.of(Catalog.item("plant-1", "Rubber Plant")), null));
        assertEquals(200, searching.handle(get(OFFERINGS + "/search")).status());

        OdpService acting = Catalog.handling(request -> Catalog.page(
                List.of(new Offering(
                        null,
                        null,
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
                        List.of(Catalog.action()),
                        null,
                        Map.of())),
                null));
        assertEquals(500, acting.handle(get(OFFERINGS + "/search")).status());
    }

    /** A failure that says when to come back says so whatever its status. */
    @Test
    void carriesRetryAfterOnAnyStatusThatNamesOne() {
        assertEquals(
                "7",
                Catalog.failing(OdpServiceException.retryable(409, "CONFLICT", "reindexing", 7))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .headers()
                        .get("Retry-After"));
    }

    @Test
    void replacesACodeLongerThanACodeCanBe() {
        assertEquals(
                "INVALID_REQUEST",
                OdpJson.read(
                                Catalog.failing(new OdpServiceException(400, "A".repeat(65), "nope"))
                                        .handle(get(OFFERINGS + "/plant-1"))
                                        .body(),
                                org.offeringprotocol.odp.core.ProblemDetails.class)
                        .code());
    }

    /** Among equally specific ranges the highest quality wins. */
    @Test
    void takesTheHighestQualityAmongEquallySpecificRanges() {
        assertEquals(
                200,
                Catalog.service()
                        .handle(Catalog.with(OFFERINGS, "Accept", "application/odp+json;q=0, application/odp+json;q=1"))
                        .status());
    }

    /** An Accept field that states no range at all states no preference. */
    @Test
    void readsAnEmptyAcceptFieldAsNoPreference() {
        assertEquals(
                200,
                Catalog.service()
                        .handle(Catalog.with(OFFERINGS, "Accept", " , "))
                        .status());
    }

    /** SVC-82 names two operations, and either one missing is the same refusal. */
    @Test
    void refusesToStandUpWithoutEitherBaselineOperation() {
        OdpService.Endpoint endpoint = new OdpService.Endpoint(
                AuthenticationRequirement.NOT_REQUIRED, request -> Catalog.offering("plant-1", "Rubber Plant"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OdpService(Catalog.template(List.of("en")), Map.of(OdpOperation.LIST_OFFERINGS, endpoint)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OdpService(Catalog.template(List.of("en")), Map.of(OdpOperation.GET_OFFERING, endpoint)));
    }

    /** A query parameter present with no value at all reads as absent. */
    @Test
    void readsAParameterWithNoValuesAsAbsent() {
        OdpHttpRequest request = new OdpHttpRequest("GET", OFFERINGS, Map.of("limit", List.of()), Map.of(), null);

        assertNull(request.queryValue("limit"));
        assertEquals(200, Catalog.service().handle(request).status());
    }

    private static Offering illustrated(String id) {
        return new Offering(
                null,
                Odp.VERSION,
                id,
                "Rubber Plant",
                null,
                List.of(
                        new ResourceImage(null, null, "https://plants.example/first.png", null, null),
                        new ResourceImage(null, null, "https://plants.example/second.png", null, null)),
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

    private static Collection pictured(String id) {
        return new Collection(
                null,
                Odp.VERSION,
                id,
                "Plants",
                null,
                List.of(
                        new ResourceImage(null, null, "https://plants.example/first.png", null, null),
                        new ResourceImage(null, null, "https://plants.example/second.png", null, null)),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private static Collection embedded(String id) {
        return new Collection(null, null, id, "Plants", null, null, null, null, null, null, null, null, Map.of());
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
