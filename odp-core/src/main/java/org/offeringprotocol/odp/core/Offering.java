package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record Offering(
        @JsonProperty("auth_expands") Boolean authExpands,
        @JsonProperty("odp_version") String odpVersion,
        String id,
        String name,
        String description,
        List<ResourceImage> images,
        String language,
        List<String> localizations,
        @JsonProperty("web_url") String webUrl,
        @JsonProperty("collection_ids") List<String> collectionIds,
        PricePreview price,
        SchemaReference schema,
        Map<String, OdpJsonNode> attributes,
        List<Action> actions,
        @JsonProperty("detail_fields") List<String> detailFields,
        @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {

    public Offering {
        images = Copies.list(images);
        localizations = Copies.list(localizations);
        collectionIds = Copies.list(collectionIds);
        attributes = Copies.nullableNodes(attributes);
        actions = Copies.list(actions);
        detailFields = Copies.list(detailFields);
        additional = Copies.nodes(additional);
    }

    public record PricePreview(
            String type,
            String amount,
            String currency,
            String minimum,
            String maximum,
            String unit,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public PricePreview {
            additional = Copies.nodes(additional);
        }
    }

    public record SchemaReference(String url) {}

    public record Action(
            AuthenticationRequirement authentication,
            String id,
            String rel,
            String description,
            HttpTarget http,
            OpenApiTarget openapi) {}

    public record HttpTarget(
            String href,
            String method,
            ActionRequest request,
            @JsonProperty("response_content_types") List<String> responseContentTypes) {
        public HttpTarget {
            responseContentTypes = Copies.list(responseContentTypes);
        }
    }

    public record ActionRequest(
            @JsonProperty("content_type") String contentType, SchemaReference schema) {}

    public record OpenApiTarget(
            @JsonProperty("operation_id") String operationId, String url) {}
}
