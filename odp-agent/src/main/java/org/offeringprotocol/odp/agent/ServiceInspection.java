package org.offeringprotocol.odp.agent;

import java.net.URI;
import java.util.Map;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.ServiceDocument;

public record ServiceInspection(
        String serviceOrigin,
        URI documentUri,
        ServiceDocument document,
        Map<OdpOperation, OperationDescriptor> operations) {
    public ServiceInspection {
        operations = Map.copyOf(operations);
    }

    public boolean supports(OdpOperation operation) {
        return operations.containsKey(operation);
    }
}
