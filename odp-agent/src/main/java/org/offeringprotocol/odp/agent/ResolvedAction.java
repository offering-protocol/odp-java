package org.offeringprotocol.odp.agent;

import tools.jackson.databind.JsonNode;

public record ResolvedAction(
        DiscoveredAction action, JsonNode requestSchema, JsonNode openApiDocument, JsonNode operation) {
    public ResolvedAction {
        requestSchema = copy(requestSchema);
        openApiDocument = copy(openApiDocument);
        operation = copy(operation);
    }

    @Override
    public JsonNode requestSchema() {
        return copy(requestSchema);
    }

    @Override
    public JsonNode openApiDocument() {
        return copy(openApiDocument);
    }

    @Override
    public JsonNode operation() {
        return copy(operation);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
