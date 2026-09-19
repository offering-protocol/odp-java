package org.offeringprotocol.odp.json.jackson2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpValidationException;

class Jackson2JsonProviderTest {
    @Test
    void decodesDirectoryTimestamps() {
        assertEquals(
                Instant.parse("2026-09-18T12:30:00Z"),
                OdpJson.treeToValue(OdpJson.parseTree("\"2026-09-18T12:30:00Z\""), Instant.class));
    }

    @Test
    void supportsTheOdpJsonContract() {
        String document = """
                {
                  "odp_version":"1.0",
                  "name":"Example Service",
                  "description":"An ODP Service.",
                  "language":"en",
                  "localizations":["en"],
                  "operations":[
                    {"authentication":"not-required","name":"get-offering"},
                    {"authentication":"not-required","name":"list-offerings"}
                  ],
                  "http":{"endpoint_base":"/odp"}
                }
                """;

        assertEquals("Example Service", OdpJson.parseServiceDocument(document).name());
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument("{}"));
    }
}
