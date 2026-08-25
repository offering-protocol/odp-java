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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        ServiceDocument document =
                parse(json, "service-document.schema.json", "Service Document", ServiceDocument.class);
        validateLocalizations(document.language(), document.localizations(), "Service Document");
        if (document.additional().containsKey("web_url")) {
            throw semanticError("Service Document", "web_url is not permitted", "/web_url");
        }
        return document;
    }

    public static Collection parseCollection(String json) {
        Collection collection = parse(json, "collection.schema.json", "Collection", Collection.class);
        validateLocalizations(collection.language(), collection.localizations(), "Collection");
        validateImages(collection.images(), "Collection");
        return collection;
    }

    public static Offering parseOffering(String json) {
        Offering offering = parse(json, "offering.schema.json", "Offering", Offering.class);
        validateLocalizations(offering.language(), offering.localizations(), "Offering");
        validateImages(offering.images(), "Offering");
        return offering;
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

    private static void validateLocalizations(String language, List<String> localizations, String documentType) {
        Set<String> normalized = new HashSet<>();
        if (localizations != null) {
            for (int index = 0; index < localizations.size(); index++) {
                String localization =
                        normalizeLanguageTag(localizations.get(index), documentType, "/localizations/" + index);
                if (!normalized.add(localization)) {
                    throw semanticError(
                            documentType,
                            "localizations must be unique without regard to case",
                            "/localizations/" + index);
                }
            }
        }
        if (language != null) {
            String normalizedLanguage = normalizeLanguageTag(language, documentType, "/language");
            if (localizations != null && !normalized.contains(normalizedLanguage)) {
                throw semanticError(documentType, "language must appear in localizations", "/language");
            }
        }
    }

    private static String normalizeLanguageTag(String value, String documentType, String path) {
        try {
            String normalized = new Locale.Builder()
                    .setLanguageTag(value)
                    .build()
                    .toLanguageTag()
                    .toLowerCase(Locale.ROOT);
            validateUniqueVariants(value, documentType, path);
            return normalized;
        } catch (java.util.IllformedLocaleException exception) {
            throw semanticError(documentType, "language tag is invalid", path, exception);
        }
    }

    private static void validateUniqueVariants(String value, String documentType, String path) {
        String[] subtags = value.split("-");
        int index = 1;
        int extlangs = 0;
        while (index < subtags.length && extlangs < 3 && isLetters(subtags[index], 3)) {
            index++;
            extlangs++;
        }
        if (index < subtags.length && isLetters(subtags[index], 4)) {
            index++;
        }
        if (index < subtags.length && (isLetters(subtags[index], 2) || isDigits(subtags[index], 3))) {
            index++;
        }
        Set<String> variants = new HashSet<>();
        while (index < subtags.length && isVariant(subtags[index])) {
            if (!variants.add(subtags[index].toLowerCase(Locale.ROOT))) {
                throw semanticError(documentType, "language tag contains a duplicate variant", path);
            }
            index++;
        }
    }

    private static boolean isVariant(String value) {
        return (value.length() >= 5 && value.length() <= 8 && value.chars().allMatch(Character::isLetterOrDigit))
                || (value.length() == 4
                        && Character.isDigit(value.charAt(0))
                        && value.chars().allMatch(Character::isLetterOrDigit));
    }

    private static boolean isLetters(String value, int length) {
        return value.length() == length && value.chars().allMatch(Character::isLetter);
    }

    private static boolean isDigits(String value, int length) {
        return value.length() == length && value.chars().allMatch(Character::isDigit);
    }

    private static void validateImages(List<ResourceImage> images, String documentType) {
        if (images == null) {
            return;
        }
        Set<String> sources = new HashSet<>();
        for (int index = 0; index < images.size(); index++) {
            if (!sources.add(images.get(index).src())) {
                throw semanticError(documentType, "image sources must be unique", "/images/" + index + "/src");
            }
        }
    }

    private static OdpValidationException semanticError(String documentType, String message, String path) {
        return semanticError(documentType, message, path, null);
    }

    private static OdpValidationException semanticError(
            String documentType, String message, String path, Throwable cause) {
        return new OdpValidationException(
                documentType, List.of(new ValidationIssue("semantic", message, Map.of(), path)), cause);
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
