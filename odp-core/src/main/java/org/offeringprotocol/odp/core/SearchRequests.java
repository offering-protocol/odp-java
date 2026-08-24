package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public interface SearchRequests {
    public record Collections(
            @JsonProperty("odp_version") String odpVersion,
            String query,
            @JsonProperty("parent_id") String parentId,
            Integer limit) {}

    public record Offerings(
            @JsonProperty("odp_version") String odpVersion,
            String query,
            List<SearchCapabilities.FilterExpression> filters,
            @JsonProperty("collection_id") String collectionId,
            @JsonProperty("include_descendants") Boolean includeDescendants,
            String sort,
            List<String> refinements,
            Integer limit) {
        public Offerings {
            filters = Copies.list(filters);
            refinements = Copies.list(refinements);
        }
    }
}
