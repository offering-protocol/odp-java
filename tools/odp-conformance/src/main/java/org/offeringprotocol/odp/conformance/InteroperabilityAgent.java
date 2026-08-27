package org.offeringprotocol.odp.conformance;

import java.net.URI;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.core.OdpOperation;

/** Verifies the Java Agent against an ODP Service. */
public final class InteroperabilityAgent {
    private static final int REQUIRED_ARGUMENTS = 1;

    private InteroperabilityAgent() {}

    public static void main(String[] arguments) {
        if (arguments.length != REQUIRED_ARGUMENTS) {
            throw new IllegalArgumentException("Usage: InteroperabilityAgent SERVICE_URL");
        }
        OdpServiceClient client = OdpServiceClient.create(URI.create(arguments[0]));
        if (client.inspection().document().name().isBlank()) {
            throw new IllegalStateException("Service name is empty");
        }
        if (!client.inspection().supports(OdpOperation.LIST_OFFERINGS)
                || !client.inspection().supports(OdpOperation.GET_OFFERING)) {
            throw new IllegalStateException("Service omitted required operations");
        }
        var offerings = client.listOfferings("terse", null, null).items();
        if (offerings.isEmpty()) {
            throw new IllegalStateException("Service returned no Offerings");
        }
        var first = offerings.get(0);
        var details = client.getOfferingDetails(first.id(), null);
        if (!first.id().equals(details.offering().id())
                || !first.name().equals(details.offering().name())) {
            throw new IllegalStateException("Full Offering does not match its listed summary");
        }
        if (!details.actions().isEmpty()) {
            var action = details.actions().get(0);
            var resolved = client.resolveAction(first.id(), action.id(), null);
            if (!action.id().equals(resolved.action().id())) {
                throw new IllegalStateException("Resolved Action identifier changed");
            }
        }
        System.out.println( // NOPMD - Console output reports successful interoperability.
                "Java Agent interoperates with the ODP Service");
    }
}
