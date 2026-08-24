package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record OfferingPage(
        @JsonProperty("auth_expands") Boolean authExpands,
        @JsonProperty("odp_version") String odpVersion,
        List<Offering> items,
        String next,
        List<RefinementGroup> refinements,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additional) {
    public OfferingPage {
        items = List.copyOf(items);
        refinements = Copies.list(refinements);
        additional = Copies.nodes(additional);
    }

    public record RefinementGroup(@JsonProperty("filter_id") String filterId, List<RefinementBucket> values) {
        public RefinementGroup {
            values = List.copyOf(values);
        }
    }

    public record RefinementBucket(
            JsonNode value,
            long count,
            @JsonProperty("count_relation") String countRelation) {
        public RefinementBucket {
            value = value.deepCopy();
        }
    }
}
