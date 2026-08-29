package org.offeringprotocol.odp.agent;

import org.offeringprotocol.odp.core.OdpJsonNode;

public record ResolvedAction(
        DiscoveredAction action, OdpJsonNode requestSchema, OdpJsonNode openApiDocument, OdpJsonNode operation) {
    public ResolvedAction {
        requestSchema = copy(requestSchema);
        openApiDocument = copy(openApiDocument);
        operation = copy(operation);
    }

    @Override
    public OdpJsonNode requestSchema() {
        return copy(requestSchema);
    }

    @Override
    public OdpJsonNode openApiDocument() {
        return copy(openApiDocument);
    }

    @Override
    public OdpJsonNode operation() {
        return copy(operation);
    }

    private static OdpJsonNode copy(OdpJsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
