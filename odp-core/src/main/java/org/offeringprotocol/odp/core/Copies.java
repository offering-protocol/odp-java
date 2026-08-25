package org.offeringprotocol.odp.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

final class Copies {
    private Copies() {}

    static <T> List<T> list(List<T> value) {
        return value == null ? null : List.copyOf(value);
    }

    static <K, V> Map<K, V> map(Map<K, V> value) {
        return value == null ? Map.of() : Map.copyOf(value);
    }

    static Map<String, JsonNode> nodes(Map<String, JsonNode> value) {
        if (value == null) {
            return Map.of();
        }
        return value.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().deepCopy()));
    }

    static Map<String, JsonNode> nullableNodes(Map<String, JsonNode> value) {
        return value == null ? null : nodes(value);
    }
}
