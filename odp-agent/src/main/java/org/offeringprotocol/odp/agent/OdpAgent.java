package org.offeringprotocol.odp.agent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.SearchRequests;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.directory.DirectoryModels;

/** Two-stage directory-to-Service Offering discovery. */
public final class OdpAgent {
    private final DirectoryClient directory;
    private final ServiceClientFactory serviceClients;

    public OdpAgent(DirectoryClient directory) {
        this(directory, origin -> OdpServiceClient.create(URI.create(origin)));
    }

    public OdpAgent(DirectoryClient directory, ServiceClientFactory serviceClients) {
        this.directory = Objects.requireNonNull(directory);
        this.serviceClients = Objects.requireNonNull(serviceClients);
    }

    public List<DiscoveryEvent> searchOfferings(String query, int maximumServices, int offeringsPerService) {
        if (maximumServices < 1 || maximumServices > 100) {
            throw new IllegalArgumentException("maximumServices must be from 1 through 100");
        }
        if (offeringsPerService < 1 || offeringsPerService > 100) {
            throw new IllegalArgumentException("offeringsPerService must be from 1 through 100");
        }
        DirectoryModels.SearchPage services =
                directory.searchServices(new DirectoryModels.SearchRequest(query, null, maximumServices));
        List<DiscoveryEvent> events = new ArrayList<>();
        for (DirectoryModels.Service service : services.items()) {
            try {
                OdpServiceClient client = serviceClients.create(service.serviceOrigin());
                if (!client.inspection().supports(org.offeringprotocol.odp.core.OdpOperation.SEARCH_OFFERINGS)) {
                    continue;
                }
                var request = new SearchRequests.Offerings(
                        org.offeringprotocol.odp.core.Odp.VERSION,
                        query,
                        null,
                        null,
                        null,
                        null,
                        null,
                        offeringsPerService);
                for (Offering offering :
                        client.searchOfferings(request, "terse", null).items()) {
                    events.add(new OfferingEvent(service, offering));
                }
            } catch (RuntimeException exception) {
                events.add(new IssueEvent(service, exception.getMessage()));
            }
        }
        return List.copyOf(events);
    }

    @FunctionalInterface
    public interface ServiceClientFactory {
        OdpServiceClient create(String serviceOrigin);
    }

    public sealed interface DiscoveryEvent permits OfferingEvent, IssueEvent {}

    public record OfferingEvent(DirectoryModels.Service service, Offering offering) implements DiscoveryEvent {}

    public record IssueEvent(DirectoryModels.Service service, String message) implements DiscoveryEvent {}
}
