package org.offeringprotocol.odp.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.offeringprotocol.odp.core.OdpJson;

/** Runnable two-stage Agent discovery example using a mock directory. */
public final class AgentDiscovery {
    private AgentDiscovery() {}

    public static void main(String[] arguments) {
        List<String> candidates = arguments.length == 0
                ? List.of("http://127.0.0.1:4101", "http://127.0.0.1:4102", "http://127.0.0.1:4103")
                : Arrays.asList(arguments);
        MockDirectory directory = MockDirectory.discover(candidates);
        System.out.printf( // NOPMD - Console output is the example's user interface.
                "Mock directory contains %d reachable ODP Service(s).%n", directory.size());
        List<org.offeringprotocol.odp.agent.OdpServiceClient> services = new ArrayList<>(directory.search("odp"));
        if (services.isEmpty()) {
            services.addAll(directory.search(""));
        }
        for (var service : services) {
            print("ODP Service Document", service.inspection().document());
            var page = service.listOfferings("terse", null, null);
            print("Terse Offering list", page);
            for (var offering : page.items()) {
                print("Full Offering " + offering.id(), service.getOfferingDetails(offering.id(), null));
            }
        }
    }

    private static void print(String label, Object value) {
        System.out.printf( // NOPMD - Console output is the example's user interface.
                "%n%s:%n%s%n", label, OdpJson.write(value));
    }
}
