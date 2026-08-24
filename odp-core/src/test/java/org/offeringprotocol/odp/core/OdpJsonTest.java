package org.offeringprotocol.odp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class OdpJsonTest {
    private static final String DOCUMENT = """
            {
              "odp_version":"1.0",
              "name":"Example Service",
              "description":"An ODP Service used by the Java tests.",
              "language":"en",
              "localizations":["en"],
              "operations":[
                {"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}
              ],
              "http":{"endpoint_base":"/odp"},
              "example_extension":{"enabled":true}
            }
            """;

    @Test
    void parsesAndPreservesServiceDocumentExtensions() {
        ServiceDocument document = OdpJson.parseServiceDocument(DOCUMENT);

        assertEquals("Example Service", document.name());
        assertTrue(document.additional().containsKey("example_extension"));
        assertTrue(OdpJson.write(document).contains("example_extension"));
    }

    @Test
    void rejectsInvalidServiceDocuments() {
        OdpValidationException exception = assertThrows(
                OdpValidationException.class, () -> OdpJson.parseServiceDocument("{\"odp_version\":\"1.0\"}"));

        assertEquals("Service Document", exception.documentType());
        assertTrue(!exception.issues().isEmpty());
    }

    @Test
    void resolvesOperationUrisAndCanonicalIdentity() {
        URI operation =
                OdpUris.buildOperationUri("/odp", OdpOperation.GET_OFFERING, "https://EXAMPLE.com:443", "plant-1");
        ResourceIdentity identity =
                ResourceIdentity.create(URI.create("https://EXAMPLE.com/.well-known/odp"), "offering", "plant-1");

        assertEquals("https://EXAMPLE.com:443/odp/offerings/plant-1", operation.toString());
        assertEquals("https://example.com\0offering\0plant-1", identity.key());
    }
}
