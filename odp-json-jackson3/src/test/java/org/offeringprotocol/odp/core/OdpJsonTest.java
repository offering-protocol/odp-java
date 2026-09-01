package org.offeringprotocol.odp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
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
    void buildsAndRoundTripsServiceDocuments() {
        List<OperationDescriptor> operations = List.of(
                new OperationDescriptor(AuthenticationRequirement.NOT_REQUIRED, OdpOperation.GET_OFFERING),
                new OperationDescriptor(AuthenticationRequirement.NOT_REQUIRED, OdpOperation.LIST_OFFERINGS));
        ServiceDocument document = ServiceDocument.builder(
                        "Example Service",
                        "An ODP Service built by the Java API.",
                        "en",
                        new ServiceDocument.Http("/odp", null))
                .keywords(List.of("example"))
                .operations(operations)
                .build();

        ServiceDocument decoded = OdpJson.parseServiceDocument(OdpJson.write(document));

        assertEquals(document, decoded);
        assertEquals(List.of("en"), decoded.localizations());
    }

    @Test
    void parsesTapTrustProtocol() {
        ServiceDocument document = OdpJson.parseServiceDocument(DOCUMENT.replace(
                "\"example_extension\":{\"enabled\":true}",
                "\"protocols\":{\"trust\":[{\"name\":\"tap\"}]},\"example_extension\":{\"enabled\":true}"));

        assertEquals(
                List.of(new ServiceDocument.TrustProtocol("tap")),
                document.protocols().trust());
    }

    @Test
    void filtersUnknownProtocolsForAgentsWithoutWeakeningServiceValidation() {
        String protocols = """
                "protocols":{
                  "enrollment":[{"name":"future-enrollment"},{"name":"aep"}],
                  "payments":[
                    {"authentication":"not-required","name":"future-payment"},
                    {"authentication":"not-required","name":"mpp"},
                    {"authentication":"not-required","name":"x402"}],
                  "trust":[{"name":"future-trust"},{"name":"tap"}]},
                """;
        String value = DOCUMENT.replace("\"example_extension\":", protocols + "\"example_extension\":");

        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(value));
        ServiceDocument document = OdpJson.parseAgentServiceDocument(value);

        assertEquals(
                List.of(new ServiceDocument.EnrollmentProtocol("aep")),
                document.protocols().enrollment());
        assertEquals(2, document.protocols().payments().size());
        assertEquals(
                List.of(new ServiceDocument.TrustProtocol("tap")),
                document.protocols().trust());
    }

    @Test
    void omitsAgentProtocolCategoriesContainingOnlyUnknownNames() {
        String protocols = """
                "protocols":{
                  "enrollment":[{"name":"future-enrollment"}],
                  "payments":[{"authentication":"not-required","name":"future-payment"}],
                  "trust":[{"name":"future-trust"}]},
                """;
        String value = DOCUMENT.replace("\"example_extension\":", protocols + "\"example_extension\":");

        assertNull(OdpJson.parseAgentServiceDocument(value).protocols());
    }

    @Test
    void rejectsMalformedRecognizedProtocolsForAgents() {
        String value = DOCUMENT.replace(
                "\"example_extension\":", "\"protocols\":{\"payments\":[{\"name\":\"mpp\"}]},\"example_extension\":");

        assertThrows(OdpValidationException.class, () -> OdpJson.parseAgentServiceDocument(value));
    }

    @Test
    void rejectsInvalidServiceDocuments() {
        OdpValidationException exception = assertThrows(
                OdpValidationException.class, () -> OdpJson.parseServiceDocument("{\"odp_version\":\"1.0\"}"));

        assertEquals("Service Document", exception.documentType());
        assertTrue(!exception.issues().isEmpty());
    }

    @Test
    void rejectsProblemTypesThatDoNotCorrespondToTheCode() {
        String problem = """
                {
                  "code":"NOT_FOUND",
                  "status":404,
                  "title":"Not found",
                  "type":"https://offeringprotocol.org/problems/validation-failed"
                }
                """;

        assertThrows(OdpValidationException.class, () -> OdpJson.parseProblemDetails(problem));
    }

    @Test
    void enforcesServiceDocumentSemanticConstraints() {
        String duplicateLocalization =
                DOCUMENT.replace("\"localizations\":[\"en\"]", "\"localizations\":[\"en\",\"EN\"]");
        String missingLanguage = DOCUMENT.replace("\"localizations\":[\"en\"]", "\"localizations\":[\"ja\"]");
        String invalidLanguage = DOCUMENT.replace("\"language\":\"en\"", "\"language\":\"en-a\"")
                .replace("\"localizations\":[\"en\"]", "\"localizations\":[\"en-a\"]");
        String duplicateVariant = DOCUMENT.replace("\"language\":\"en\"", "\"language\":\"sl-rozaj-rozaj\"")
                .replace("\"localizations\":[\"en\"]", "\"localizations\":[\"sl-rozaj-rozaj\"]");
        String prohibitedWebUrl = DOCUMENT.replace(
                "\"example_extension\":{\"enabled\":true}",
                "\"web_url\":\"/store/\",\"example_extension\":{\"enabled\":true}");

        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(duplicateLocalization));
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(missingLanguage));
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(invalidLanguage));
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(duplicateVariant));
        assertThrows(OdpValidationException.class, () -> OdpJson.parseServiceDocument(prohibitedWebUrl));
    }

    @Test
    void rejectsDuplicateOfferingImageSources() {
        String offering = """
                {
                  "odp_version":"1.0",
                  "id":"desk",
                  "name":"Standing desk",
                  "images":[{"src":"/desk.webp"},{"src":"/desk.webp"}]
                }
                """;

        assertThrows(OdpValidationException.class, () -> OdpJson.parseOffering(offering));
    }

    @Test
    void preservesAbsentOfferingAttributes() {
        Offering offering = OdpJson.parseOffering("""
                {"odp_version":"1.0","id":"desk","name":"Standing desk"}
                """);

        assertNull(offering.attributes());
        assertTrue(!OdpJson.write(offering).contains("attributes"));
    }

    @Test
    void validatesEmbeddedRepresentationsWithThePageVersion() {
        Page<Collection> collections = OdpJson.parsePage(
                "{\"items\":[{\"description\":\"odp_version\",\"id\":\"plants\",\"name\":\"Plants\"}],\"odp_version\":\"1.0\"}",
                Collection.class);
        OfferingPage offerings = OdpJson.parseOfferingSearchResponse(
                "{\"items\":[{\"id\":\"plant\",\"name\":\"Plant\"}],\"odp_version\":\"1.0\"}");

        assertNull(collections.items().get(0).odpVersion());
        assertNull(offerings.items().get(0).odpVersion());
        assertThrows(
                OdpValidationException.class,
                () -> OdpJson.parseOfferingSearchResponse(
                        "{\"items\":[{\"id\":\"bad/id\",\"name\":\"Plant\"}],\"odp_version\":\"1.0\"}"));
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
