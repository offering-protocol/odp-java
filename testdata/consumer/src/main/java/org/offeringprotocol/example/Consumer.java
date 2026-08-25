package org.offeringprotocol.example;

import java.util.List;
import org.offeringprotocol.odp.agent.OdpAgent;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.directory.DirectoryModels;
import org.offeringprotocol.odp.service.OdpService;
import org.offeringprotocol.odp.service.StaticCatalog;

public final class Consumer {
    private Consumer() {}

    public static void main(String[] args) {
        DirectoryClient directory = DirectoryClient.create();
        OdpAgent agent = new OdpAgent(directory);
        DirectoryModels.SearchRequest request = new DirectoryModels.SearchRequest("plants", null, 10);
        Offering offering = OdpJson.parseOffering("""
                {
                  "odp_version": "1.0",
                  "id": "rubber-plant",
                  "name": "Rubber Plant"
                }
                """);
        OdpService service = OdpService.builder(
                        "Example Plant Store", "Indoor plants.", "en", "/odp")
                .endpoints(StaticCatalog.create(List.of(offering), List.of()))
                .build();
        System.out.println(List.of(Odp.VERSION, agent, request, service.document()));
    }
}
