package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record SearchCapabilities(
        FilterSource filters,
        SortSource sorts,
        @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
    public SearchCapabilities {
        additional = Copies.nodes(additional);
    }

    public record Link(String href) {}

    public record FilterSource(List<FilterDefinition> inline, Link linked) {
        public FilterSource {
            inline = Copies.list(inline);
        }
    }

    public record SortSource(List<SortDefinition> inline, Link linked) {
        public SortSource {
            inline = Copies.list(inline);
        }
    }

    public record FilterDefinition(
            String id,
            String title,
            String description,
            String type,
            List<String> operators,
            FilterUnit unit,
            Boolean refinable,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public FilterDefinition {
            operators = List.copyOf(operators);
            additional = Copies.nodes(additional);
        }
    }

    public record FilterUnit(
            String system,
            String code,
            String title,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public FilterUnit {
            additional = Copies.nodes(additional);
        }
    }

    public record SortDefinition(
            String id,
            String title,
            String description,
            List<SortKey> keys,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public SortDefinition {
            keys = List.copyOf(keys);
            additional = Copies.nodes(additional);
        }
    }

    public record SortKey(
            @JsonProperty("filter_id") String filterId,
            String direction,
            String missing,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public SortKey {
            additional = Copies.nodes(additional);
        }
    }

    public record FilterExpression(
            String id,
            String operator,
            OdpJsonNode value,
            @JsonAnySetter @JsonAnyGetter Map<String, OdpJsonNode> additional) {
        public FilterExpression {
            value = value.deepCopy();
            additional = Copies.nodes(additional);
        }
    }
}
