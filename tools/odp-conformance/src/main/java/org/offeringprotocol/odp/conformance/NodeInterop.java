package org.offeringprotocol.odp.conformance;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.core.OdpOperation;

/** Verifies the Java Agent against the Node.js reference Service. */
public final class NodeInterop {
    private static final int REQUIRED_ARGUMENTS = 1;

    private NodeInterop() {}

    public static void main(String[] arguments) {
        if (arguments.length != REQUIRED_ARGUMENTS) {
            throw new IllegalArgumentException("Usage: NodeInterop SERVICE_URL");
        }
        String serviceUrl = arguments[0];
        OdpServiceClient client = OdpServiceClient.create(URI.create(serviceUrl));
        if (!"Small Example Store".equals(client.inspection().document().name())) {
            throw new IllegalStateException("Java Agent inspected an unexpected Node.js Service");
        }
        if (!client.inspection().supports(OdpOperation.LIST_OFFERINGS)
                || !client.inspection().supports(OdpOperation.GET_OFFERING)) {
            throw new IllegalStateException("Node.js Service omitted required operations");
        }
        Set<String> identifiers = client.listOfferings("terse", null, null).items().stream()
                .map(offering -> offering.id())
                .collect(Collectors.toSet());
        if (!identifiers.containsAll(Set.of("architecture-review", "incident-plan"))) {
            throw new IllegalStateException("Node.js Service omitted expected Offerings");
        }
        var offering = client.getOffering("incident-plan", "full", null);
        if (!"Incident Response Plan".equals(offering.name())
                || offering.price() == null
                || !"free".equals(offering.price().type())
                || offering.actions() == null
                || offering.actions().stream().noneMatch(action -> "download".equals(action.id()))) {
            throw new IllegalStateException("Node.js full Offering did not match its advertised catalog");
        }
        System.out.println( // NOPMD - Console output reports successful interoperability.
                "Java Agent interoperates with the Node.js example Service");
    }
}
