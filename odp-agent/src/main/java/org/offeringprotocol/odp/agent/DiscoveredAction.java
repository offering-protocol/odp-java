package org.offeringprotocol.odp.agent;

import java.util.List;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Offering;

public record DiscoveredAction(
        AuthenticationRequirement authentication,
        String id,
        String rel,
        String description,
        HttpTarget http,
        OpenApiTarget openapi) {

    public record HttpTarget(
            String url, String method, Offering.ActionRequest request, List<String> responseContentTypes) {
        public HttpTarget {
            responseContentTypes = responseContentTypes == null ? List.of() : List.copyOf(responseContentTypes);
        }
    }

    public record OpenApiTarget(String url, String operationId) {}
}
