package org.offeringprotocol.odp.core;

import java.net.URI;

public record ResourceIdentity(String service, String type, String id) {
    public static ResourceIdentity create(URI serviceDocumentUri, String type, String id) {
        if (!"collection".equals(type) && !"offering".equals(type)) {
            throw new IllegalArgumentException("Invalid ODP resource type");
        }
        if (!OdpUris.isLocalResourceIdentifier(id)) {
            throw new IllegalArgumentException("Invalid ODP local resource identifier");
        }
        return new ResourceIdentity(OdpUris.deriveServiceOrigin(serviceDocumentUri), type, id);
    }

    public String key() {
        return service + '\0' + type + '\0' + id;
    }
}
