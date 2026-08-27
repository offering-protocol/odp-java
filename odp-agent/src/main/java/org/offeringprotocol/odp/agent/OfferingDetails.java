package org.offeringprotocol.odp.agent;

import java.util.List;
import org.offeringprotocol.odp.core.Offering;
import tools.jackson.databind.JsonNode;

public record OfferingDetails(
        Offering offering, JsonNode attributeSchema, List<DiscoveredAction> actions, List<OfferingIssue> issues) {
    public OfferingDetails {
        if (attributeSchema != null) {
            attributeSchema = attributeSchema.deepCopy();
        }
        actions = actions == null ? List.of() : List.copyOf(actions);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    @Override
    public JsonNode attributeSchema() {
        return attributeSchema == null ? null : attributeSchema.deepCopy();
    }
}
