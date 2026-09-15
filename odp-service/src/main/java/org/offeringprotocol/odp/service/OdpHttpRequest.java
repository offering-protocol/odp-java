package org.offeringprotocol.odp.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One inbound HTTP request, as whatever framework is in front of this Service describes it.
 *
 * <p>{@code path} is the decoded request path: percent-encoded octets are decoded by the framework
 * before they reach here, so a path segment naming a resource is the identifier itself. Absent
 * query or header maps read as empty ones.
 */
public record OdpHttpRequest(
        String method, String path, Map<String, List<String>> query, Map<String, List<String>> headers, String body) {
    public OdpHttpRequest {
        query = copy(query);
        headers = copy(headers);
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> values) {
        if (values == null) {
            return Map.of();
        }
        return values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    /** The first value of a query parameter, or null. Repeated parameters are refused upstream. */
    public String queryValue(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** The first value of a header field, compared without regard to case. */
    public String headerValue(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }
}
