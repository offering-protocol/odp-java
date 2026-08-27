package org.offeringprotocol.odp.agent;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.offeringprotocol.odp.core.OdpJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class AttributeSchemaResolver {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final String DEFINITIONS = "$defs";
    private static final String IDENTIFIER = "$id";
    private static final String STANDARD_VOCABULARY = "https://json-schema.org/draft/2020-12/vocab/";
    private static final int MAXIMUM_DEPTH = 8;
    private static final int MAXIMUM_DOCUMENT_BYTES = 262_144;
    private static final int MAXIMUM_DOCUMENTS = 16;
    private static final int MAXIMUM_GRAPH_BYTES = 1_048_576;

    private final SupportingJsonClient client;

    AttributeSchemaResolver(SupportingJsonClient client) {
        this.client = client;
    }

    ResolvedSchema resolve(URI reference) {
        URI root = withoutFragment(reference);
        SchemaGraph graph = new SchemaGraph();
        load(root, 0, graph);
        JsonNode bundled = bundle(root, graph.documents);
        Map<String, String> registryDocuments = new HashMap<>();
        graph.documents.forEach((url, document) -> registryDocuments.put(url.toString(), document.toString()));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(registryDocuments));
        Schema validator = registry.getSchema(SchemaLocation.of(root.toString()));
        return new ResolvedSchema(bundled, validator);
    }

    private void load(URI target, int depth, SchemaGraph graph) {
        if (graph.documents.containsKey(target)) {
            return;
        }
        if (graph.documents.size() >= MAXIMUM_DOCUMENTS) {
            throw new IllegalStateException("ODP Attribute Schema graph exceeds 16 documents");
        }
        if (depth > MAXIMUM_DEPTH) {
            throw new IllegalStateException("ODP Attribute Schema graph exceeds eight reference levels");
        }
        JsonNode document = client.get(
                target, "application/schema+json", Set.of("application/schema+json"), MAXIMUM_DOCUMENT_BYTES, 16);
        requireSchema(document);
        graph.bytes += document.toString().getBytes(StandardCharsets.UTF_8).length;
        if (graph.bytes > MAXIMUM_GRAPH_BYTES) {
            throw new IllegalStateException("ODP Attribute Schema graph exceeds its byte limit");
        }
        graph.documents.put(target, document);
        for (URI external : externalReferences(document, target)) {
            load(external, depth + 1, graph);
        }
    }

    private static void requireSchema(JsonNode document) {
        if (!DIALECT.equals(document.path("$schema").asString())) {
            throw new IllegalStateException("ODP Attribute Schema must declare JSON Schema Draft 2020-12");
        }
        visit(document, node -> {
            JsonNode dynamicReference = node.get("$dynamicRef");
            if (dynamicReference != null
                    && (!dynamicReference.isString()
                            || !dynamicReference.asString().startsWith("#"))) {
                throw new IllegalStateException("ODP Attribute Schema $dynamicRef must be a fragment-only reference");
            }
            JsonNode vocabulary = node.get("$vocabulary");
            if (vocabulary != null && vocabulary.isObject()) {
                vocabulary.forEachEntry((uri, required) -> {
                    if (required.asBoolean(false) && !uri.startsWith(STANDARD_VOCABULARY)) {
                        throw new IllegalStateException("ODP Attribute Schema requires unsupported vocabulary " + uri);
                    }
                });
            }
        });
    }

    private static List<URI> externalReferences(JsonNode document, URI retrievalUrl) {
        Set<URI> localResources = new HashSet<>();
        collectResourceIdentifiers(document, retrievalUrl, localResources);
        Set<URI> references = new HashSet<>();
        collectReferences(document, retrievalUrl, references);
        references.removeAll(localResources);
        return references.stream().sorted().toList();
    }

    private static void collectResourceIdentifiers(JsonNode value, URI base, Set<URI> result) {
        URI current = resolveIdentifier(value, base);
        result.add(withoutFragment(current));
        for (JsonNode child : value) {
            collectResourceIdentifiers(child, current, result);
        }
    }

    private static void collectReferences(JsonNode value, URI base, Set<URI> result) {
        URI current = resolveIdentifier(value, base);
        JsonNode reference = value.isObject() ? value.get("$ref") : null;
        if (reference != null) {
            if (!reference.isString()) {
                throw new IllegalStateException("ODP Attribute Schema $ref must be a string");
            }
            URI resolved = current.resolve(reference.asString());
            requireHttps(resolved, "ODP Attribute Schema references must use HTTPS");
            result.add(withoutFragment(resolved));
        }
        for (JsonNode child : value) {
            collectReferences(child, current, result);
        }
    }

    private static URI resolveIdentifier(JsonNode value, URI base) {
        JsonNode identifier = value.isObject() ? value.get(IDENTIFIER) : null;
        if (identifier == null) {
            return base;
        }
        if (!identifier.isString()) {
            throw new IllegalStateException("ODP Attribute Schema $id must be a string");
        }
        URI resolved = base.resolve(identifier.asString());
        requireHttps(resolved, "ODP Attribute Schema identifiers must use HTTPS");
        return resolved;
    }

    private static JsonNode bundle(URI rootUrl, Map<URI, JsonNode> documents) {
        ObjectNode root = documents.get(rootUrl).deepCopy().asObject();
        if (!root.has(IDENTIFIER)) {
            root.put(IDENTIFIER, rootUrl.toString());
        }
        List<URI> externalUrls = new ArrayList<>(documents.keySet());
        externalUrls.remove(rootUrl);
        externalUrls.sort(URI::compareTo);
        if (!externalUrls.isEmpty()) {
            ObjectNode definitions =
                    root.has(DEFINITIONS) && root.get(DEFINITIONS).isObject()
                            ? root.get(DEFINITIONS).deepCopy().asObject()
                            : root.putObject(DEFINITIONS);
            int index = 0;
            for (URI externalUrl : externalUrls) {
                String key = "odp_external_" + index;
                index++;
                while (definitions.has(key)) {
                    key = key + "_";
                }
                ObjectNode external = documents.get(externalUrl).deepCopy().asObject();
                if (!external.has(IDENTIFIER)) {
                    external.put(IDENTIFIER, externalUrl.toString());
                }
                definitions.set(key, external);
            }
            root.set(DEFINITIONS, definitions);
        }
        return root;
    }

    private static void visit(JsonNode value, NodeVisitor visitor) {
        if (value.isObject()) {
            visitor.visit(value);
        }
        for (JsonNode child : value) {
            visit(child, visitor);
        }
    }

    private static URI withoutFragment(URI value) {
        requireHttps(value, "ODP Attribute Schema URL must use HTTPS");
        String text = value.toString();
        int fragment = text.indexOf('#');
        return URI.create(fragment == -1 ? text : text.substring(0, fragment));
    }

    private static void requireHttps(URI value, String message) {
        if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null) {
            throw new IllegalStateException(message);
        }
    }

    record ResolvedSchema(JsonNode document, Schema validator) {
        boolean validates(Map<String, JsonNode> attributes) {
            return validator
                    .validate(OdpJson.write(attributes), InputFormat.JSON)
                    .isEmpty();
        }
    }

    private static final class SchemaGraph {
        private final Map<URI, JsonNode> documents = new LinkedHashMap<>();
        private int bytes;
    }

    @FunctionalInterface
    private interface NodeVisitor {
        void visit(JsonNode value);
    }
}
