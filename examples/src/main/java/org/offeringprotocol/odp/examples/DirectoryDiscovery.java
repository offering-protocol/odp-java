package org.offeringprotocol.odp.examples;

import java.net.URI;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.directory.DirectoryEnvironment;
import org.offeringprotocol.odp.directory.DirectoryModels;

/** Mixed discovery through the canonical Directory, followed by anonymous Collection retrieval. */
public final class DirectoryDiscovery {
    private DirectoryDiscovery() {}

    public static void main(String[] arguments) {
        DirectoryEnvironment environment =
                switch (arguments.length == 0 ? "production" : arguments[0]) {
                    case "sandbox" -> DirectoryEnvironment.SANDBOX;
                    case "production" -> DirectoryEnvironment.PRODUCTION;
                    default -> throw new IllegalArgumentException("Environment must be production or sandbox");
                };
        String query = arguments.length > 1 ? arguments[1] : null;
        DirectoryClient directory = DirectoryClient.create(environment);
        var response = directory.search(new DirectoryModels.ResourceSearchRequest(query, null, 5, null));
        for (var issue : response.issues()) {
            print("Skipped result " + issue.index(), issue.message());
        }
        for (var result : response.items()) {
            if (result instanceof DirectoryModels.ServiceResult service) {
                print(
                        "Service",
                        service.service().name() + " — " + service.service().serviceOrigin());
            } else if (result instanceof DirectoryModels.CollectionResult collection) {
                print(
                        "Collection",
                        collection.collection().name() + " — "
                                + collection.service().serviceOrigin());
                OdpServiceClient client =
                        OdpServiceClient.create(URI.create(collection.service().serviceOrigin()));
                boolean anonymous = client.inspection().document().operations().stream()
                        .anyMatch(operation -> operation.name() == OdpOperation.GET_COLLECTION
                                && operation.authentication() != AuthenticationRequirement.REQUIRED);
                if (anonymous) {
                    print(
                            "Full Collection",
                            OdpJson.write(
                                    client.getCollection(collection.collection().id(), "full", null)));
                } else {
                    print("Collection details", "The Service does not advertise anonymous Collection retrieval.");
                }
            } else {
                print("Unsupported result type", result.type());
            }
        }
    }

    private static void print(String label, String value) {
        System.out.printf("%s: %s%n", label, value); // NOPMD - Console output is the example's user interface.
    }
}
