package org.offeringprotocol.odp.directory;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.ServiceDocument;

final class DirectoryResults {
    private static final String FIELD_FACETS = "facets";
    private static final String FIELD_NEXT = "next";
    private static final String FIELD_SERVICE = "service";
    private static final String FIELD_COLLECTION = "collection";
    private static final String FIELD_SERVICE_ID = "service_id";
    private static final String FIELD_SERVICE_ORIGIN = "service_origin";
    private static final String FIELD_INDEXED_AT = "indexed_at";
    private static final String FIELD_AVAILABLE_THROUGH = "available_through";
    private static final String FIELD_NAME = "name";

    private DirectoryResults() {}

    static DirectoryModels.SearchResponse decode(String json) {
        OdpJsonNode value = object(OdpJson.parseTree(json), "Directory response");
        OdpJsonNode items = value.get("items");
        if (items == null || !items.isArray() || items.size() > 100) {
            throw new IllegalArgumentException("Directory response items are invalid");
        }
        List<DirectoryModels.Result> results = new ArrayList<>();
        List<DirectoryModels.Issue> issues = new ArrayList<>();
        int index = 0;
        for (OdpJsonNode item : items) {
            try {
                results.add(result(item));
            } catch (IllegalArgumentException exception) {
                issues.add(new DirectoryModels.Issue(index, exception.getMessage()));
            }
            index++;
        }
        DirectoryModels.Facets facets = value.has(FIELD_FACETS)
                        && !value.get(FIELD_FACETS).isNull()
                ? OdpJson.treeToValue(object(value.get(FIELD_FACETS), FIELD_FACETS), DirectoryModels.Facets.class)
                : null;
        DirectoryClient.validateFacets(facets);
        String next = value.has(FIELD_NEXT) && !value.get(FIELD_NEXT).isNull() ? text(value, FIELD_NEXT, 2048) : null;
        return new DirectoryModels.SearchResponse(
                results, next, facets, issues, additional(value, Set.of("items", FIELD_NEXT, FIELD_FACETS)));
    }

    private static DirectoryModels.Result result(OdpJsonNode value) {
        object(value, "Directory result");
        String type = text(value, "type", 128);
        if (!FIELD_SERVICE.equals(type) && !FIELD_COLLECTION.equals(type)) {
            return new DirectoryModels.UnknownResult(type, value);
        }
        OdpJsonNode serviceNode =
                object(value.get(FIELD_SERVICE), FIELD_SERVICE).deepCopy();
        text(serviceNode, FIELD_SERVICE_ID, 128);
        origin(serviceNode, FIELD_SERVICE_ORIGIN);
        instant(serviceNode, FIELD_INDEXED_AT);
        serviceNode.remove(List.of("branding", "http", "mcp", "odp_version", "payment_origins", "search_capabilities"));
        OdpJsonNode document = serviceNode.deepCopy();
        document.remove(List.of(FIELD_SERVICE_ID, FIELD_SERVICE_ORIGIN, FIELD_INDEXED_AT));
        document.put("odp_version", "1.0");
        document.putObject("http").put("endpoint_base", "/");
        ServiceDocument parsed = OdpJson.parseAgentServiceDocument(document.toString());
        serviceNode.set("operations", OdpJson.valueToTree(parsed.operations()));
        if (parsed.protocols() == null) {
            serviceNode.remove("protocols");
        } else {
            serviceNode.set("protocols", OdpJson.valueToTree(parsed.protocols()));
        }
        DirectoryModels.Service service = OdpJson.treeToValue(serviceNode, DirectoryModels.Service.class);
        Instant indexedAt = instant(value, FIELD_INDEXED_AT);
        if (FIELD_SERVICE.equals(type)) {
            DirectoryModels.ServiceReference reference =
                    value.has(FIELD_AVAILABLE_THROUGH) ? reference(value.get(FIELD_AVAILABLE_THROUGH)) : null;
            return new DirectoryModels.ServiceResult(
                    service,
                    indexedAt,
                    reference,
                    additional(value, Set.of("type", FIELD_SERVICE, FIELD_INDEXED_AT, FIELD_AVAILABLE_THROUGH)));
        }
        OdpJsonNode collection = object(value.get(FIELD_COLLECTION), FIELD_COLLECTION);
        String id = text(collection, "id", 128);
        if (!OdpUris.isLocalResourceIdentifier(id)) {
            throw new IllegalArgumentException("collection.id must be a local resource identifier");
        }
        String description = null;
        if (collection.has("description")) {
            OdpJsonNode raw = collection.get("description");
            if (!raw.isString() || raw.asString().length() > 1024) {
                throw new IllegalArgumentException("collection.description is invalid");
            }
            description = raw.asString();
        }
        return new DirectoryModels.CollectionResult(
                service,
                indexedAt,
                new DirectoryModels.CollectionSummary(
                        id,
                        text(collection, FIELD_NAME, 128),
                        description,
                        additional(collection, Set.of("id", FIELD_NAME, "description"))),
                additional(value, Set.of("type", FIELD_SERVICE, FIELD_INDEXED_AT, FIELD_COLLECTION)));
    }

    private static DirectoryModels.ServiceReference reference(OdpJsonNode value) {
        object(value, FIELD_AVAILABLE_THROUGH);
        return new DirectoryModels.ServiceReference(
                text(value, FIELD_SERVICE_ID, 128),
                origin(value, FIELD_SERVICE_ORIGIN),
                value.has(FIELD_NAME) ? text(value, FIELD_NAME, 128) : null,
                additional(value, Set.of(FIELD_SERVICE_ID, FIELD_SERVICE_ORIGIN, FIELD_NAME)));
    }

    private static String origin(OdpJsonNode value, String name) {
        String text = text(value, name, 2048);
        URI uri = URI.create(text);
        if (!"https".equals(uri.getScheme()) || !text.equals(OdpUris.deriveServiceOrigin(uri))) {
            throw new IllegalArgumentException(name + " must be a canonical HTTPS origin");
        }
        return text;
    }

    private static Instant instant(OdpJsonNode value, String name) {
        try {
            return Instant.parse(text(value, name, 64));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(name + " must be a date-time", exception);
        }
    }

    private static OdpJsonNode object(OdpJsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value;
    }

    private static String text(OdpJsonNode value, String name, int maximum) {
        OdpJsonNode node = value.get(name);
        if (node == null
                || !node.isString()
                || node.asString().isBlank()
                || node.asString().length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return node.asString();
    }

    private static Map<String, OdpJsonNode> additional(OdpJsonNode value, Set<String> known) {
        Map<String, OdpJsonNode> additional = new LinkedHashMap<>();
        value.forEachEntry((name, member) -> {
            if (!known.contains(name)) {
                additional.put(name, member.deepCopy());
            }
        });
        return additional;
    }
}
