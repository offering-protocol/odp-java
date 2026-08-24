package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record Page<T>(
        @JsonProperty("auth_expands") Boolean authExpands,
        @JsonProperty("odp_version") String odpVersion,
        List<T> items,
        String next,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additional) {
    public Page {
        items = List.copyOf(items);
        additional = Copies.map(additional);
    }
}
