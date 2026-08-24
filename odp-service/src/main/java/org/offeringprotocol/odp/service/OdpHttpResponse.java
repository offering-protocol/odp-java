package org.offeringprotocol.odp.service;

import java.util.Map;

public record OdpHttpResponse(int status, Map<String, String> headers, String body) {
    public OdpHttpResponse {
        headers = Map.copyOf(headers);
    }
}
