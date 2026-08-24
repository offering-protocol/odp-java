package org.offeringprotocol.odp.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record OdpHttpRequest(
        String method, String path, Map<String, List<String>> query, Map<String, List<String>> headers, String body) {
    public OdpHttpRequest {
        query = query.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        headers = headers.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    public String queryValue(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String headerValue(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
