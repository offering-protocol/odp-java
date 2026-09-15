package org.offeringprotocol.odp.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ServiceDocument;

/** The fixtures the Service tests are written against. */
final class Catalog {
    static final String BASE = "/odp";
    static final String OFFERINGS = BASE + "/offerings";
    static final String COLLECTIONS = BASE + "/collections";
    static final String ODP_JSON = "application/odp+json";

    private Catalog() {}

    /** A Service over two Offerings in one Collection, with every static handler registered. */
    static OdpService service() {
        return new OdpService(
                template(List.of("en")),
                StaticCatalog.create(
                        List.of(offering("plant-1", "Rubber Plant"), offering("plant-2", "Snake Plant")),
                        List.of(collection("plants"))));
    }

    /** The same Service, answering in whichever of these languages the request asks for. */
    static OdpService multilingual(String... localizations) {
        return new OdpService(
                template(List.of(localizations)),
                StaticCatalog.create(List.of(offering("plant-1", "Rubber Plant")), List.of()));
    }

    /** A Service whose list and get handlers are whatever a test wants them to be. */
    static OdpService handling(CatalogHandler handler) {
        Map<OdpOperation, OdpService.Endpoint> endpoints =
                new EnumMap<>(StaticCatalog.create(List.of(offering("plant-1", "Rubber Plant")), List.of()));
        OdpService.Endpoint endpoint = new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, handler);
        endpoints.put(OdpOperation.GET_OFFERING, endpoint);
        endpoints.put(OdpOperation.LIST_OFFERINGS, endpoint);
        endpoints.put(OdpOperation.SEARCH_OFFERINGS, endpoint);
        return new OdpService(template(List.of("en")), endpoints);
    }

    static OdpService failing(RuntimeException thrown) {
        return handling(request -> {
            throw thrown;
        });
    }

    static ServiceDocument template(List<String> localizations) {
        return ServiceDocument.builder(
                        "Plant Store", "Plants for agents.", localizations.get(0), new ServiceDocument.Http(BASE, null))
                .localizations(localizations)
                .operations(List.of())
                .build();
    }

    static Offering offering(String id, String name) {
        return offering(id, name, null, null);
    }

    static Offering offering(String id, String name, List<Offering.Action> actions, List<String> detailFields) {
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
                actions,
                detailFields,
                Map.of());
    }

    /** A page item, which inherits the version of the document that carries it. */
    static Offering item(String id, String name) {
        return new Offering(
                null, null, id, name, null, null, null, null, null, null, null, null, null, null, null, Map.of());
    }

    static Collection collection(String id) {
        return collection(id, null);
    }

    static Collection collection(String id, List<String> detailFields) {
        return new Collection(
                null, Odp.VERSION, id, "Plants", null, null, null, null, null, null, null, detailFields, Map.of());
    }

    static Offering.Action action() {
        return new Offering.Action(
                AuthenticationRequirement.NOT_REQUIRED,
                "purchase",
                "purchase",
                null,
                new Offering.HttpTarget("/purchase", "POST", null, null),
                null);
    }

    static Page<Offering> page(List<Offering> items, String next) {
        return new Page<>(null, Odp.VERSION, items, next, Map.of());
    }

    // -- requests ---------------------------------------------------------------------------

    static OdpHttpRequest get(String path) {
        return new OdpHttpRequest("GET", path, Map.of(), Map.of(), null);
    }

    static OdpHttpRequest get(String path, Map<String, List<String>> query) {
        return new OdpHttpRequest("GET", path, query, Map.of(), null);
    }

    static OdpHttpRequest with(String path, String header, String value) {
        return new OdpHttpRequest("GET", path, Map.of(), Map.of(header, List.of(value)), null);
    }

    static OdpHttpRequest post(String path, String contentType, String body) {
        Map<String, List<String>> headers =
                contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType));
        return new OdpHttpRequest("POST", path, Map.of(), headers, body);
    }

    static Map<String, List<String>> query(String name, String... values) {
        return Map.of(name, List.of(values));
    }
}
