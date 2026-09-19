package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;

class DirectoryResultsTest {
    static final String SERVICE = """
            {"service_id":"ca0304cc-ab28-43e5-af94-7bdf11b40c6e",
             "service_origin":"https://api.example.com","name":"Example Service",
             "description":"Data services.","language":"en","localizations":["en"],
             "operations":[{"name":"get-offering","authentication":"not-required"},
               {"name":"list-offerings","authentication":"not-required"}],
             "protocols":{"trust":[{"name":"tap"},{"name":"future"}]},
             "indexed_at":"2026-09-18T11:00:00Z"}
            """;

    static OdpJsonNode result(String type) {
        OdpJsonNode node = OdpJson.parseTree("""
                {"type":"collection","indexed_at":"2026-09-18T12:00:00Z",
                 "collection":{"id":"Weather","name":"Weather forecasts","description":"Forecasts."}}
                """);
        node.put("type", type);
        node.set("service", OdpJson.parseTree(SERVICE));
        if ("service".equals(type)) {
            node.remove("collection");
        }
        return node;
    }

    static String response(OdpJsonNode... items) {
        return "{\"items\":" + OdpJson.write(Arrays.asList(items)) + "}";
    }

    @Test
    void readsKnownAndUnknownResultsWithAttributionAndFreshness() {
        OdpJsonNode service = result("service");
        service.set("available_through", OdpJson.parseTree("""
                {"service_id":"platform","service_origin":"https://platform.example","name":"Platform","extra":true}
                """));
        service.put("extra", "retained");
        OdpJsonNode collection = result("collection");
        collection.get("collection").put("extra", "retained");
        OdpJsonNode unknown = OdpJson.parseTree("{\"type\":\"future\",\"nested\":{\"field\":1}}");
        var decoded = DirectoryResults.decode(response(service, collection, unknown));
        assertTrue(decoded.issues().isEmpty());
        var first = assertInstanceOf(
                DirectoryModels.ServiceResult.class, decoded.items().get(0));
        assertEquals("service", first.type());
        assertEquals("Platform", first.availableThrough().name());
        assertEquals("ca0304cc-ab28-43e5-af94-7bdf11b40c6e", first.service().serviceId());
        assertEquals("retained", first.additional().get("extra").asString());
        assertTrue(first.availableThrough().additional().get("extra").asBoolean(false));
        assertEquals(1, first.service().protocols().trust().size());
        var second = assertInstanceOf(
                DirectoryModels.CollectionResult.class, decoded.items().get(1));
        assertEquals("collection", second.type());
        assertEquals("Weather", second.collection().id());
        assertEquals("retained", second.collection().additional().get("extra").asString());
        assertEquals("2026-09-18T12:00:00Z", second.indexedAt().toString());
        assertEquals("2026-09-18T11:00:00Z", second.service().indexedAt().toString());
        var third = assertInstanceOf(
                DirectoryModels.UnknownResult.class, decoded.items().get(2));
        assertEquals(unknown.toString(), third.raw().toString());
        third.raw().put("changed", "not retained");
        assertEquals(unknown.toString(), third.raw().toString());
        assertThrows(UnsupportedOperationException.class, () -> decoded.items().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> first.additional().clear());
    }

    @Test
    void reportsMalformedKnownEntriesWithoutLosingValidOnes() {
        List<Consumer<OdpJsonNode>> changes = List.of(
                node -> node.remove("type"),
                node -> node.put("type", " "),
                node -> node.remove("service"),
                node -> node.get("service").remove("service_id"),
                node -> node.get("service").put("service_origin", "http://api.example.com"),
                node -> node.get("service").put("service_origin", "https://api.example.com/path"),
                node -> node.get("service").remove("operations"),
                node -> node.put("indexed_at", "yesterday"),
                node -> node.remove("indexed_at"),
                node -> node.remove("collection"),
                node -> node.get("collection").put("id", "../outside"),
                node -> node.get("collection").put("name", ""),
                node -> node.get("collection").set("description", OdpJson.parseTree("null")),
                node -> node.get("collection").put("description", "x".repeat(1025)));
        for (var change : changes) {
            OdpJsonNode invalid = result("collection");
            change.accept(invalid);
            var decoded = DirectoryResults.decode(response(invalid, result("collection")));
            assertEquals(1, decoded.items().size(), invalid.toString());
            assertEquals(1, decoded.issues().size(), invalid.toString());
            assertEquals(0, decoded.issues().get(0).index());
        }
        for (String reference : List.of(
                "null",
                "{}",
                "false",
                "{\"service_id\":\"x\",\"service_origin\":\"https://user@platform.example\"}",
                "{\"service_id\":\"x\",\"service_origin\":\"https://platform.example\",\"name\":null}")) {
            OdpJsonNode invalid = result("service");
            invalid.set("available_through", OdpJson.parseTree(reference));
            assertEquals(1, DirectoryResults.decode(response(invalid)).issues().size());
        }
    }

    @Test
    void acceptsOptionalFieldsAndRemovesUnverifiedExecutionMetadata() {
        OdpJsonNode service = result("service");
        service.get("service").remove("protocols");
        service.get("service").put("http", "unverified");
        service.get("service").set("operations", OdpJson.parseTree("""
                [{"name":"get-offering","authentication":"not-required"},
                 {"name":"list-offerings","authentication":"not-required"},
                 {"name":"future-operation","authentication":"not-required"}]
                """));
        service.set("available_through", OdpJson.parseTree("""
                {"service_id":"x","service_origin":"https://platform.example"}
                """));
        var decoded = DirectoryResults.decode(response(service));
        var item = assertInstanceOf(
                DirectoryModels.ServiceResult.class, decoded.items().get(0));
        assertNull(item.availableThrough().name());
        assertNull(item.service().protocols());
        assertNull(item.service().additional().get("http"));
        assertEquals(2, item.service().operations().size());
        service.remove("available_through");
        assertNull(assertInstanceOf(
                        DirectoryModels.ServiceResult.class,
                        DirectoryResults.decode(response(service)).items().get(0))
                .availableThrough());
        for (boolean omit : List.of(true, false)) {
            OdpJsonNode collection = result("collection");
            if (omit) {
                collection.get("collection").remove("description");
            } else {
                collection.get("collection").put("description", "");
            }
            assertTrue(DirectoryResults.decode(response(collection)).issues().isEmpty());
        }
    }

    @Test
    void validatesEnvelopeAndPreservesFacetsAndContinuation() {
        for (String body : List.of(
                "null",
                "[]",
                "{}",
                "{\"items\":null}",
                "{\"items\":{}}",
                "{\"items\":[],\"next\":false}",
                "{\"items\":[],\"facets\":false}")) {
            assertThrows(IllegalArgumentException.class, () -> DirectoryResults.decode(body), body);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectoryResults.decode(response(
                        java.util.Collections.nCopies(101, result("service")).toArray(OdpJsonNode[]::new))));
        var decoded = DirectoryResults.decode("""
                {"items":[],"next":"/v1/directory/search?cursor=opaque","extra":true,
                 "facets":{"keywords":[{"value":"weather","count":12}],"trust":[{"value":{"name":"tap"},"count":3}]}}
                """);
        assertEquals("/v1/directory/search?cursor=opaque", decoded.next());
        assertEquals(12, decoded.facets().keywords().get(0).count());
        assertEquals(3, decoded.facets().trust().get(0).count());
        assertTrue(decoded.additional().get("extra").asBoolean(false));
    }

    @Test
    void validatesAndSnapshotsMixedRequests() {
        List<String> types = new ArrayList<>(List.of("service", "collection"));
        var request = new DirectoryModels.ResourceSearchRequest("weather", null, 10, types);
        types.clear();
        assertEquals(List.of("service", "collection"), request.types());
        assertEquals(
                "{\"query\":\"weather\",\"limit\":10,\"types\":[\"service\",\"collection\"]}", OdpJson.write(request));
        assertEquals("{}", OdpJson.write(new DirectoryModels.ResourceSearchRequest(null, null, null, null)));
        for (List<String> invalid : List.of(
                List.<String>of(), List.of("future"), List.of("service", "service"), Arrays.asList("service", null))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DirectoryModels.ResourceSearchRequest(null, null, null, invalid));
        }
        assertThrows(
                IllegalArgumentException.class, () -> new DirectoryModels.ResourceSearchRequest(" ", null, null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DirectoryModels.ResourceSearchRequest("x".repeat(513), null, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new DirectoryModels.ResourceSearchRequest(null, null, 101, null));
    }
}
