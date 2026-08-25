package org.offeringprotocol.odp.examples;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.offeringprotocol.odp.agent.OdpServiceClient;

/** Reachable Service index used only by the Agent example. */
final class MockDirectory {
    private final Map<String, OdpServiceClient> services;

    private MockDirectory(Map<String, OdpServiceClient> services) {
        this.services = Map.copyOf(services);
    }

    static MockDirectory discover(List<String> candidates) {
        Map<String, OdpServiceClient> services = new LinkedHashMap<>();
        for (String candidate : candidates) {
            try {
                OdpServiceClient client = OdpServiceClient.create(URI.create(candidate));
                services.put(client.inspection().serviceOrigin(), client);
            } catch (RuntimeException ignored) {
                // Unreachable candidates do not appear in the mock directory.
            }
        }
        if (services.isEmpty()) {
            throw new IllegalStateException("No configured ODP Services are reachable");
        }
        return new MockDirectory(services);
    }

    List<OdpServiceClient> search(String query) {
        String normalized = query.toLowerCase(java.util.Locale.ROOT);
        List<OdpServiceClient> matches = new ArrayList<>();
        for (OdpServiceClient client : services.values()) {
            var document = client.inspection().document();
            String metadata = document.name() + " " + document.description() + " "
                    + String.join(" ", document.keywords() == null ? List.of() : document.keywords());
            if (metadata.toLowerCase(java.util.Locale.ROOT).contains(normalized)) {
                matches.add(client);
            }
        }
        return List.copyOf(matches);
    }

    int size() {
        return services.size();
    }
}
