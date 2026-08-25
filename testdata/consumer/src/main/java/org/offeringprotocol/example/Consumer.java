package org.offeringprotocol.example;

import java.util.List;
import org.offeringprotocol.odp.agent.OdpAgent;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.service.OdpService;

public final class Consumer {
    private Consumer() {}

    public static void main(String[] args) {
        System.out.println(List.of(Odp.class, DirectoryClient.class, OdpAgent.class, OdpService.class));
    }
}
