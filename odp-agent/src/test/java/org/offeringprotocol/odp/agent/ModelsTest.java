package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.OdpJson;

class ModelsTest {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** An omitted list reads as an empty one, so a caller never has to check for null. */
    @Test
    void readsAnOmittedListAsAnEmptyOne() {
        DiscoveredAction.HttpTarget target = new DiscoveredAction.HttpTarget("https://x.example/a", "POST", null, null);
        assertTrue(target.responseContentTypes().isEmpty());

        OfferingDetails details = new OfferingDetails(null, null, null, null);
        assertTrue(details.actions().isEmpty());
        assertTrue(details.issues().isEmpty());
        assertNull(details.attributeSchema());

        ResolvedAction resolved = new ResolvedAction(
                new DiscoveredAction(AuthenticationRequirement.NOT_REQUIRED, "rent", "purchase", null, target, null),
                null,
                null,
                null);
        assertNull(resolved.requestSchema());
        assertNull(resolved.openApiDocument());
        assertNull(resolved.operation());
    }

    /** A caller cannot reach into a document the Agent validated, so every copy handed out is its own. */
    @Test
    void handsOutACopyOfEveryDocument() {
        var schema = OdpJson.parseTree("{\"type\":\"object\"}");
        OfferingDetails details = new OfferingDetails(null, schema, List.of(), List.of());
        assertNotSame(schema, details.attributeSchema());
        assertNotSame(details.attributeSchema(), details.attributeSchema());

        ResolvedAction resolved = new ResolvedAction(null, schema, schema, schema);
        assertNotSame(schema, resolved.requestSchema());
        assertNotSame(resolved.openApiDocument(), resolved.openApiDocument());
        assertNotSame(resolved.operation(), resolved.operation());
    }

    /** A document two branches both reference is retrieved once, and its fragment is not part of it. */
    @Test
    void retrievesASharedSchemaDocumentOnce() {
        Map<String, String> documents = Map.of(
                "https://schemas.example/root.json",
                "{\"$schema\":\"" + DIALECT + "\",\"properties\":{"
                        + "\"left\":{\"$ref\":\"left.json\"},\"right\":{\"$ref\":\"right.json\"}}}",
                "https://schemas.example/left.json",
                "{\"$schema\":\"" + DIALECT + "\",\"$ref\":\"shared.json#/$defs/value\"}",
                "https://schemas.example/right.json",
                "{\"$schema\":\"" + DIALECT + "\",\"$ref\":\"shared.json#/$defs/value\"}",
                "https://schemas.example/shared.json",
                "{\"$schema\":\"" + DIALECT + "\",\"$defs\":{\"value\":{\"type\":\"integer\"}}}");
        Map<String, Integer> requests = new HashMap<>();
        OdpServiceClient client = OdpServiceClient.create(
                URI.create("https://plants.example"),
                request -> Responses.ok(
                        request,
                        request.uri().getPath().equals("/.well-known/odp")
                                ? Responses.SERVICE_DOCUMENT
                                : "{\"odp_version\":\"1.0\",\"id\":\"gpu\",\"name\":\"GPU\","
                                        + "\"schema\":{\"url\":\"https://schemas.example/root.json\"}}"),
                request -> {
                    requests.merge(request.uri().toString(), 1, Integer::sum);
                    return Responses.of(
                            request,
                            200,
                            documents.get(request.uri().toString()),
                            Map.of("Content-Type", List.of("application/schema+json")));
                });
        OfferingDetails details = client.getOfferingDetails("gpu", null);
        assertTrue(details.issues().isEmpty(), details.issues().toString());
        assertEquals(1, requests.get("https://schemas.example/shared.json"));
        assertEquals(4, requests.size());
    }
}
