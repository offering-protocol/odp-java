package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.SearchRequests;

class RepresentationConformanceTest {
    private static final String ACTION = """
            {"authentication":"not-required","id":"rent","rel":"purchase",
            "http":{"href":"/rent","method":"POST"}}
            """;

    /** A Terse Representation carries no Actions, so a Service sending them answered a different request. */
    @Test
    void refusesActionsInATerseOffering() {
        OdpServiceClient page = Responses.serving(request ->
                "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"gpu\",\"name\":\"GPU\",\"actions\":[" + ACTION + "]}]}");
        assertEquals(
                "ODP Terse Offering cannot contain Actions",
                assertThrows(IllegalArgumentException.class, () -> page.listOfferings(null, null, null))
                        .getMessage());

        OdpServiceClient single = Responses.serving(
                request -> "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\",\"actions\":[" + ACTION + "]}");
        assertEquals(
                "ODP Terse Offering cannot contain Actions",
                assertThrows(IllegalArgumentException.class, () -> single.getOffering("gpu", "terse", null))
                        .getMessage());
        // The same document is the expected answer to a request for the Full Representation.
        assertEquals("GPU", single.getOffering("gpu", "full", null).name());
    }

    /** A Full Representation withholds nothing, so it has no detail_fields to list. */
    @Test
    void refusesDetailFieldsInAFullRepresentation() {
        OdpServiceClient offering = Responses.serving(request ->
                "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\",\"detail_fields\":[\"/description\"]}");
        assertEquals(
                "ODP Full Offering cannot contain detail_fields",
                assertThrows(IllegalArgumentException.class, () -> offering.getOffering("gpu", "full", null))
                        .getMessage());
        assertEquals("GPU", offering.getOffering("gpu", "terse", null).name());

        OdpServiceClient collection = Responses.serving(request ->
                "{\"odp_version\":\"1.0\",\"id\":\"pots\",\"name\":\"Pots\",\"detail_fields\":[\"/description\"]}");
        assertEquals(
                "ODP Full Collection cannot contain detail_fields",
                assertThrows(IllegalArgumentException.class, () -> collection.getCollection("pots", "full", null))
                        .getMessage());
        assertEquals("Pots", collection.getCollection("pots", "terse", null).name());
    }

    /** VER-03: a page item inherits its container's version and cannot restate it. */
    @Test
    void refusesAPageItemThatRestatesTheVersion() {
        OdpServiceClient offerings = Responses.serving(request ->
                "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"gpu\",\"name\":\"GPU\",\"odp_version\":\"1.0\"}]}");
        assertEquals(
                "ODP page item Offering cannot restate odp_version",
                assertThrows(IllegalArgumentException.class, () -> offerings.listOfferings(null, null, null))
                        .getMessage());

        OdpServiceClient collections = Responses.serving(request ->
                "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"pots\",\"name\":\"Pots\",\"odp_version\":\"1.0\"}]}");
        assertEquals(
                "ODP page item Collection cannot restate odp_version",
                assertThrows(IllegalArgumentException.class, () -> collections.listCollections(null, null, null))
                        .getMessage());

        // A single resource is a Top-Level Document and carries the version its page items must not.
        OdpServiceClient single =
                Responses.serving(request -> "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\"}");
        assertEquals("GPU", single.getOffering("gpu", null, null).name());
    }

    @Test
    void refusesAnItemWithoutAUsableSummary() {
        OdpServiceClient nameless =
                Responses.serving(request -> "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"gpu\",\"name\":\"  \"}]}");
        assertEquals(
                "Offering summary is invalid",
                assertThrows(IllegalArgumentException.class, () -> nameless.listOfferings(null, null, null))
                        .getMessage());
    }

    @Test
    void servesEveryOperationTheServiceAdvertises() {
        OdpServiceClient client =
                Responses.serving(request -> switch (request.uri().getPath()) {
                    case "/odp/collections/pots" -> "{\"odp_version\":\"1.0\",\"id\":\"pots\",\"name\":\"Pots\"}";
                    case "/odp/offerings/gpu" -> "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\"}";
                    default -> "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"gpu\",\"name\":\"GPU\"}]}";
                });
        assertEquals(1, client.listCollections(null, null, null).items().size());
        assertEquals(
                1,
                client.listCollectionOfferings("pots", null, 10, null).items().size());
        assertEquals(1, client.listOfferings(null, null, null).items().size());
        assertEquals("Pots", client.getCollection("pots", null, null).name());
        assertEquals("GPU", client.getOffering("gpu", null, null).name());
        assertEquals(
                1,
                client.searchCollections(new SearchRequests.Collections("1.0", "pots", null, null), null, null)
                        .items()
                        .size());
        assertEquals(
                1,
                client.searchOfferings(
                                new SearchRequests.Offerings("1.0", "gpu", null, null, null, null, null, null),
                                null,
                                null)
                        .items()
                        .size());
        assertEquals(
                1,
                client.continueCollections("/odp/collections?cursor=c", null)
                        .items()
                        .size());
        assertEquals(
                1,
                client.continueOfferings("/odp/offerings?cursor=c", null)
                        .items()
                        .size());
    }

    @Test
    void refusesAnOperationTheServiceDoesNotAdvertise() {
        String minimal = """
                {"odp_version":"1.0","name":"Plant Store","description":"Plants for agents.",
                "language":"en","localizations":["en"],"operations":[
                {"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}],
                "http":{"endpoint_base":"/odp"}}
                """;
        OdpServiceClient client = OdpServiceClient.create(
                URI.create("https://plants.example"), request -> Responses.ok(request, minimal));
        assertTrue(client.inspection().supports(OdpOperation.LIST_OFFERINGS));
        for (String operation : List.of("list-collections", "get-collection", "list-collection-offerings")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case "list-collections" -> client.listCollections(null, null, null);
                    case "get-collection" -> client.getCollection("pots", null, null);
                    default -> client.listCollectionOfferings("pots", null, null, null);
                }
            });
            assertEquals("Service does not advertise " + operation, failure.getMessage());
        }
    }

    @Test
    void refusesRequestArgumentsOutsideTheirBounds() {
        OdpServiceClient client = Responses.serving(request -> "{\"odp_version\":\"1.0\",\"items\":[]}");
        assertEquals(
                "representation must be terse or full",
                assertThrows(IllegalArgumentException.class, () -> client.listOfferings("brief", null, null))
                        .getMessage());
        assertEquals(
                "limit must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> client.listOfferings(null, 0, null))
                        .getMessage());
        assertEquals(
                "limit must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> client.listOfferings(null, 101, null))
                        .getMessage());
    }

    /** PAG-07: a continuation is followed only while it stays on the Service origin. */
    @Test
    void refusesAContinuationThatLeavesTheServiceOrigin() {
        OdpServiceClient client = Responses.serving(request -> "{\"odp_version\":\"1.0\",\"items\":[]}");
        assertThrows(
                IllegalArgumentException.class,
                () -> client.continueOfferings("https://elsewhere.example/odp/offerings", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.continueCollections("https://elsewhere.example/odp/collections", null));
    }
}
