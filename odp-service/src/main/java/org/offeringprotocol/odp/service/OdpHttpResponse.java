package org.offeringprotocol.odp.service;

import java.util.Map;

/** One outbound HTTP response. An absent header map reads as an empty one. */
public record OdpHttpResponse(int status, Map<String, String> headers, String body) {
    public OdpHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }
}
