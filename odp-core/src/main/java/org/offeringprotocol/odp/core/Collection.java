package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record Collection(
        @JsonProperty("auth_expands") Boolean authExpands,
        @JsonProperty("odp_version") String odpVersion,
        String id,
        String name,
        String description,
        List<ResourceImage> images,
        String language,
        List<String> localizations,
        @JsonProperty("parent_ids") List<String> parentIds,
        @JsonProperty("web_url") String webUrl,
        @JsonProperty("search_capabilities") SearchCapabilities searchCapabilities,
        @JsonProperty("detail_fields") List<String> detailFields,
        @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
    public Collection {
        images = Copies.list(images);
        localizations = Copies.list(localizations);
        parentIds = Copies.list(parentIds);
        detailFields = Copies.list(detailFields);
        additional = Copies.nodes(additional);
    }
}
