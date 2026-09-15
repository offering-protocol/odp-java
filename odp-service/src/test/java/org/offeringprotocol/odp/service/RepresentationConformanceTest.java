package org.offeringprotocol.odp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.offeringprotocol.odp.service.Catalog.COLLECTIONS;
import static org.offeringprotocol.odp.service.Catalog.OFFERINGS;
import static org.offeringprotocol.odp.service.Catalog.get;
import static org.offeringprotocol.odp.service.Catalog.query;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.Page;

class RepresentationConformanceTest {
    private final OdpService service = Catalog.service();

    /** VER-01: every Top-Level Document declares the version that governs it. */
    @Test
    void declaresTheVersionOfEveryDocumentItSends() {
        for (String path : List.of(Odp.SERVICE_DOCUMENT_PATH, OFFERINGS, OFFERINGS + "/plant-1", COLLECTIONS)) {
            assertEquals(1, occurrences(service.handle(get(path)).body(), "\"odp_version\":\"1.0\""), path);
        }
    }

    /** VER-03/VER-04: an item inherits the version of the document carrying it, and never restates it. */
    @Test
    void refusesAPageWhoseItemsRestateTheVersion() {
        OdpService restating =
                Catalog.handling(request -> Catalog.page(List.of(Catalog.offering("plant-1", "Rubber Plant")), null));

        OdpHttpResponse response = restating.handle(get(OFFERINGS));
        assertEquals(500, response.status());
        assertTrue(response.body().contains("INTERNAL_ERROR"));

        OdpService inheriting =
                Catalog.handling(request -> Catalog.page(List.of(Catalog.item("plant-1", "Rubber Plant")), null));
        assertEquals(200, inheriting.handle(get(OFFERINGS)).status());
    }

    /** REP-12: a Full Representation contains the fields themselves, not a list of what is missing. */
    @Test
    void refusesDetailFieldsInAFullRepresentation() {
        OdpService offering =
                Catalog.handling(request -> Catalog.offering("plant-1", "Rubber Plant", null, List.of("/price")));
        assertEquals(
                500,
                offering.handle(get(OFFERINGS + "/plant-1", query("representation", "full")))
                        .status());
        assertEquals(200, offering.handle(get(OFFERINGS + "/plant-1")).status(), "REP-11 allows them in a Terse one");

        OdpService collecting = withCollection(List.of("/description"));
        assertEquals(
                500,
                collecting
                        .handle(get(COLLECTIONS + "/plants", query("representation", "full")))
                        .status());
        assertEquals(200, collecting.handle(get(COLLECTIONS + "/plants")).status());
    }

    /** OFR-55: a Terse Offering advertises its Actions through detail_fields, it does not carry them. */
    @Test
    void refusesActionsInATerseOffering() {
        OdpService acting = Catalog.handling(
                request -> Catalog.offering("plant-1", "Rubber Plant", List.of(Catalog.action()), null));

        assertEquals(500, acting.handle(get(OFFERINGS + "/plant-1")).status());
        assertEquals(
                200,
                acting.handle(get(OFFERINGS + "/plant-1", query("representation", "full")))
                        .status());
    }

    /** The static catalog serves a Terse representation by default and a Full one when asked. */
    @Test
    void servesTheRepresentationThatWasAskedFor() {
        OdpService acting = new OdpService(
                Catalog.template(List.of("en")),
                StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "Rubber Plant", List.of(Catalog.action()), null)),
                        List.of()));

        assertFalse(acting.handle(get(OFFERINGS + "/plant-1")).body().contains("actions"));
        assertTrue(acting.handle(get(OFFERINGS + "/plant-1", query("representation", "full")))
                .body()
                .contains("actions"));
    }

    /** ERR-19: a document this Service would refuse from anybody else is not one it sends. */
    @Test
    void refusesToSendADocumentPastItsLimits() {
        OdpService enormous = Catalog.handling(request -> Map.of("filler", "f".repeat(600_000)));
        OdpHttpResponse response = enormous.handle(get(OFFERINGS + "/plant-1"));

        assertEquals(500, response.status());
        assertTrue(response.body().contains("ODP response exceeds its limits"));
        assertFalse(response.body().contains("fff"));
    }

    @Test
    void refusesToSendADocumentNestedPastItsLimit() {
        OdpService deep = Catalog.handling(request -> nest(20));
        assertEquals(500, deep.handle(get(OFFERINGS + "/plant-1")).status());
        assertEquals(
                500,
                Catalog.handling(request -> nest(3))
                        .handle(get(OFFERINGS + "/plant-1"))
                        .status(),
                "shallow enough to measure, but still not an Offering");
    }

    /** PAG-06/07: a continuation is a bounded reference back to this Service. */
    @Test
    void refusesAContinuationItCouldNotFollowItself() {
        for (String next :
                List.of("https://elsewhere.example/odp/offerings", "//elsewhere.example/x", "offerings?c=2")) {
            OdpService wandering = Catalog.handling(request -> Catalog.page(List.of(), next));
            assertEquals(500, wandering.handle(get(OFFERINGS)).status(), next);
        }
        OdpService verbose =
                Catalog.handling(request -> Catalog.page(List.of(), "/odp/offerings?cursor=" + "c".repeat(2_048)));
        assertEquals(500, verbose.handle(get(OFFERINGS)).status());

        OdpService sane = Catalog.handling(request -> Catalog.page(List.of(), "/odp/offerings?cursor=c2"));
        assertEquals(200, sane.handle(get(OFFERINGS)).status());
    }

    /** PAG-11: a continuation that hands back the cursor it was given never ends. */
    @Test
    void refusesAContinuationThatDoesNotAdvance() {
        OdpService stuck = Catalog.handling(request -> Catalog.page(List.of(), "/odp/offerings?cursor=c1"));

        assertEquals(500, stuck.handle(get(OFFERINGS, query("cursor", "c1"))).status());
        assertEquals(200, stuck.handle(get(OFFERINGS, query("cursor", "c0"))).status());
        assertEquals(200, stuck.handle(get(OFFERINGS)).status());
    }

    /** A page whose next is not a reference at all is not a page this Service can send. */
    @Test
    void refusesANextThatIsNotAReference() {
        OdpService numbered = Catalog.handling(request -> new Page<>(
                null,
                Odp.VERSION,
                List.of(),
                null,
                Map.of("next", org.offeringprotocol.odp.core.OdpJson.parseTree("7"))));

        assertEquals(500, numbered.handle(get(OFFERINGS)).status());
    }

    /** A handler with nothing to return is answered as an absent resource, not an empty one. */
    @Test
    void answersAnAbsentResourceRatherThanSerializingNothing() {
        OdpService empty = Catalog.handling(request -> null);
        OdpHttpResponse response = empty.handle(get(OFFERINGS + "/plant-1"));

        assertEquals(404, response.status());
        assertTrue(response.body().contains("NOT_FOUND"));
    }

    private OdpService withCollection(List<String> detailFields) {
        Map<org.offeringprotocol.odp.core.OdpOperation, OdpService.Endpoint> endpoints =
                new java.util.EnumMap<>(StaticCatalog.create(
                        List.of(Catalog.offering("plant-1", "Rubber Plant")), List.of(Catalog.collection("plants"))));
        endpoints.put(
                org.offeringprotocol.odp.core.OdpOperation.GET_COLLECTION,
                new OdpService.Endpoint(
                        org.offeringprotocol.odp.core.AuthenticationRequirement.NOT_REQUIRED,
                        request -> Catalog.collection("plants", detailFields)));
        return new OdpService(Catalog.template(List.of("en")), endpoints);
    }

    private static Map<String, Object> nest(int levels) {
        Map<String, Object> value = Map.of("odp_version", Odp.VERSION, "id", "plant-1", "name", "Rubber Plant");
        for (int index = 0; index < levels; index++) {
            value = Map.of("nested", value);
        }
        return value;
    }

    private static int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
