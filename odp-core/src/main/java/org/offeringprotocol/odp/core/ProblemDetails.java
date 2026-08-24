package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public record ProblemDetails(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        @JsonProperty("invalid_params") List<InvalidParameter> invalidParams,
        @JsonAnySetter @JsonAnyGetter Map<String, JsonNode> additional) {
    public ProblemDetails {
        invalidParams = Copies.list(invalidParams);
        additional = Copies.nodes(additional);
    }

    public record InvalidParameter(String in, String name, String reason) {}
}
