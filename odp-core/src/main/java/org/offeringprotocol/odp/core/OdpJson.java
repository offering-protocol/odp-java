package org.offeringprotocol.odp.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/** Validated JSON encoding and decoding for ODP documents. */
public final class OdpJson {
    private static final String SCHEMA_ORIGIN = "https://offeringprotocol.org/schemas/";
    private static final String SCHEMA_PATH = "/org/offeringprotocol/odp/core/schemas/";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final SchemaRegistry SCHEMAS = createRegistry();

    private OdpJson() {}

    public static ServiceDocument parseServiceDocument(String json) {
        return parse(json, "service-document.schema.json", "Service Document", ServiceDocument.class);
    }

    public static Collection parseCollection(String json) {
        return parse(json, "collection.schema.json", "Collection", Collection.class);
    }

    public static Offering parseOffering(String json) {
        return parse(json, "offering.schema.json", "Offering", Offering.class);
    }

    public static ProblemDetails parseProblemDetails(String json) {
        return parse(json, "problem-details.schema.json", "Problem Details", ProblemDetails.class);
    }

    public static SearchRequests.Collections parseCollectionSearchRequest(String json) {
        return parse(
                json,
                "collection-search-request.schema.json",
                "Collection search request",
                SearchRequests.Collections.class);
    }

    public static SearchRequests.Offerings parseOfferingSearchRequest(String json) {
        return parse(
                json, "offering-search-request.schema.json", "Offering search request", SearchRequests.Offerings.class);
    }

    public static OfferingPage parseOfferingSearchResponse(String json) {
        return parse(json, "offering-search-response.schema.json", "Offering search response", OfferingPage.class);
    }

    public static <T> Page<T> parsePage(String json, Class<T> itemType) {
        validate(json, "page-envelope.schema.json", "page envelope");
        JavaType type = MAPPER.getTypeFactory().constructParametricType(Page.class, itemType);
        return decode(json, type, "page envelope");
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to encode ODP JSON", exception);
        }
    }

    private static <T> T parse(String json, String schemaName, String documentType, Class<T> type) {
        validate(json, schemaName, documentType);
        return decode(json, MAPPER.getTypeFactory().constructType(type), documentType);
    }

    private static <T> T decode(String json, JavaType type, String documentType) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException exception) {
            throw new OdpValidationException(
                    documentType,
                    List.of(new ValidationIssue("json", exception.getMessage(), Map.of(), "")),
                    exception);
        }
    }

    private static void validate(String json, String schemaName, String documentType) {
        Schema schema = SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_ORIGIN + schemaName));
        List<com.networknt.schema.Error> errors = schema.validate(
                json,
                InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            List<ValidationIssue> issues = errors.stream()
                    .map(error -> new ValidationIssue(
                            error.getKeyword(),
                            error.getMessage(),
                            Map.of(),
                            error.getInstanceLocation().toString()))
                    .toList();
            throw new OdpValidationException(documentType, issues);
        }
    }

    private static SchemaRegistry createRegistry() {
        Map<String, String> schemas = new HashMap<>();
        for (String name :
                readSchema("index.txt").lines().filter(line -> !line.isBlank()).toList()) {
            schemas.put(SCHEMA_ORIGIN + name, readSchema(name));
        }
        return SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(schemas));
    }

    private static String readSchema(String name) {
        try (InputStream input = OdpJson.class.getResourceAsStream(SCHEMA_PATH + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled ODP schema " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled ODP schema " + name, exception);
        }
    }
}
