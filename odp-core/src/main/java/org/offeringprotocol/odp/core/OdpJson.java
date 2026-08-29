package org.offeringprotocol.odp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/** Validated JSON encoding and decoding for ODP documents. */
public final class OdpJson {
    private static final String FIELD_NAME = "name";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_URL = "url";
    private static final String OFFERING = "offering";
    private static final int REQUIRED_PROVIDER_COUNT = 1;
    private static final String SCHEMA_ORIGIN = "https://offeringprotocol.org/schemas/";
    private static final String SCHEMA_PATH = "/org/offeringprotocol/odp/core/schemas/";
    private static final String SERVICE_DOCUMENT = "Service Document";
    private static final OdpJsonProvider PROVIDER = loadProvider();
    private static final Map<String, String> SCHEMA_DOCUMENTS = readSchemas();
    private static final Map<String, OdpJsonSchema> SCHEMAS = compileSchemas();

    private OdpJson() {}

    public static ServiceDocument parseServiceDocument(String json) {
        ServiceDocument document = parse(json, "service-document.schema.json", SERVICE_DOCUMENT, ServiceDocument.class);
        validateLocalizations(document.language(), document.localizations(), SERVICE_DOCUMENT);
        if (document.additional().containsKey("web_url")) {
            throw semanticError(SERVICE_DOCUMENT, "web_url is not permitted", "/web_url");
        }
        return document;
    }

    public static ServiceDocument parseAgentServiceDocument(String json) {
        return parseServiceDocument(normalizeAgentResponse(json, "service-document"));
    }

    public static String normalizeAgentResponse(String json, String kind) {
        try {
            OdpJsonNode value = PROVIDER.parseTree(json);
            if (value == null || !value.isObject()) {
                return json;
            }
            normalizeAgentDocument(value, kind);
            return value.toString();
        } catch (IllegalArgumentException exception) {
            throw new OdpValidationException(
                    "Agent response",
                    List.of(new ValidationIssue("json", exception.getMessage(), Map.of(), "")),
                    exception);
        }
    }

    private static void normalizeAgentDocument(OdpJsonNode document, String kind) {
        switch (kind) {
            case "service-document" -> {
                filterAgentProtocols(document);
                OdpJsonNode protocols = document.get("protocols");
                if (protocols != null && protocols.isObject()) {
                    filterUnknownAuthentication(protocols, "payments");
                }
                filterNamedList(
                        document,
                        "operations",
                        Set.of(
                                "get-collection",
                                "get-offering",
                                "list-collection-offerings",
                                "list-collections",
                                "list-offerings",
                                "search-collections",
                                "search-offerings"));
                filterUnknownAuthentication(document, "operations");
                filterTypedList(document, "mcp", Set.of("streamable-http"));
                filterClosedObjectList(document, "operations", Set.of("authentication", FIELD_NAME));
                filterClosedObjectList(document, "mcp", Set.of("description", FIELD_NAME, FIELD_TYPE, FIELD_URL));
                filterPaymentOptions(document);
                normalizeBranding(document);
                normalizeSearchCapabilities(document);
            }
            case "collection", OFFERING -> {
                filterTypedList(
                        document,
                        "images",
                        Set.of("image/avif", "image/jpeg", "image/png", "image/svg+xml", "image/webp"));
                stripObjectList(document, "images", Set.of("alt", "height", "src", FIELD_TYPE, "width"));
                normalizeSearchCapabilities(document);
                if (OFFERING.equals(kind)) {
                    normalizeOffering(document);
                }
            }
            case "collection-page", "offering-page" -> {
                OdpJsonNode items = document.get("items");
                if (items != null && items.isArray()) {
                    String itemKind = "offering-page".equals(kind) ? OFFERING : "collection";
                    items.forEach(item -> {
                        if (item.isObject()) {
                            normalizeAgentDocument(item, itemKind);
                        }
                    });
                }
            }
            case "filter-page" -> filterDefinitions(document, true);
            case "sort-page" -> filterDefinitions(document, false);
            case "problem" -> filterProblemParameters(document);
            default -> {}
        }
    }

    private static void filterNamedList(OdpJsonNode document, String member, Set<String> recognized) {
        filterList(document, member, FIELD_NAME, recognized);
    }

    private static void filterTypedList(OdpJsonNode document, String member, Set<String> recognized) {
        filterList(document, member, FIELD_TYPE, recognized);
    }

    private static void filterList(OdpJsonNode document, String member, String discriminator, Set<String> recognized) {
        OdpJsonNode value = document.get(member);
        if (value == null || !value.isArray()) {
            return;
        }
        value.removeIf(item -> {
            OdpJsonNode field = item.isObject() ? item.get(discriminator) : null;
            return field != null && field.isString() && !recognized.contains(field.asString());
        });
        if (value.isEmpty()) {
            document.remove(member);
        }
    }

    private static void filterClosedObjectList(OdpJsonNode document, String member, Set<String> allowed) {
        OdpJsonNode value = document.get(member);
        if (value == null || !value.isArray()) {
            return;
        }
        value.removeIf(item -> item.isObject() && item.fieldNames().stream().anyMatch(name -> !allowed.contains(name)));
        if (value.isEmpty()) {
            document.remove(member);
        }
    }

    private static void filterUnknownAuthentication(OdpJsonNode document, String member) {
        OdpJsonNode value = document.get(member);
        if (value == null || !value.isArray()) {
            return;
        }
        value.removeIf(item -> item.isObject() && hasUnknownAuthentication(item));
        if (value.isEmpty()) {
            document.remove(member);
        }
    }

    private static boolean hasUnknownAuthentication(OdpJsonNode value) {
        OdpJsonNode authentication = value.get("authentication");
        return authentication != null
                && authentication.isString()
                && !Set.of("not-required", "optional", "required").contains(authentication.asString());
    }

    private static void stripObjectList(OdpJsonNode document, String member, Set<String> allowed) {
        OdpJsonNode value = document.get(member);
        if (value == null || !value.isArray()) {
            return;
        }
        value.forEach(item -> {
            if (item.isObject()) {
                List<String> unknown = item.fieldNames().stream()
                        .filter(name -> !allowed.contains(name))
                        .toList();
                item.remove(unknown);
            }
        });
    }

    private static void filterPaymentOptions(OdpJsonNode document) {
        OdpJsonNode payments = document.at("/protocols/payments");
        if (!payments.isArray()) {
            return;
        }
        Set<String> recognized = Set.of(
                "algorand",
                "aptos",
                "arbitrum",
                "avalanche",
                "base",
                "card",
                "ethereum",
                "hedera",
                "inflow",
                "lightning",
                "polygon",
                "solana",
                "stellar",
                "stripe",
                "tempo",
                "ton");
        payments.forEach(payment -> {
            OdpJsonNode options = payment.get("options");
            if (payment.isObject() && options != null && options.isArray()) {
                options.removeIf(option -> option.isString() && !recognized.contains(option.asString()));
                if (options.isEmpty()) {
                    payment.remove("options");
                }
            }
        });
    }

    private static void normalizeBranding(OdpJsonNode document) {
        OdpJsonNode value = document.get("branding");
        if (value == null || !value.isObject()) {
            return;
        }
        List<String> unknownMembers = value.fieldNames().stream()
                .filter(name -> !Set.of("icon", "logo").contains(name))
                .toList();
        value.remove(unknownMembers);
        Set<String> recognized = Set.of("image/png", "image/svg+xml", "image/webp");
        for (String member : List.of("icon", "logo")) {
            OdpJsonNode type = value.at("/" + member + "/" + FIELD_TYPE);
            if (type.isString() && !recognized.contains(type.asString())) {
                value.remove(member);
            } else if (value.has(member) && value.get(member).isObject()) {
                OdpJsonNode image = value.get(member);
                List<String> unknown = image.fieldNames().stream()
                        .filter(name -> !Set.of("src", FIELD_TYPE).contains(name))
                        .toList();
                image.remove(unknown);
            }
        }
        if (value.isEmpty()) {
            document.remove("branding");
        }
    }

    private static void normalizeSearchCapabilities(OdpJsonNode document) {
        OdpJsonNode value = document.get("search_capabilities");
        if (value == null || !value.isObject()) {
            return;
        }
        filterInlineDefinitions(value, "filters", true);
        filterInlineDefinitions(value, "sorts", false);
        if (value.isEmpty()) {
            document.remove("search_capabilities");
        }
    }

    private static void filterInlineDefinitions(OdpJsonNode capabilities, String member, boolean filters) {
        OdpJsonNode source = capabilities.get(member);
        OdpJsonNode inline = source == null ? null : source.get("inline");
        if (source == null || !source.isObject() || inline == null || !inline.isArray()) {
            return;
        }
        inline.removeIf(item -> item.isObject() && !(filters ? knownFilter(item) : knownSort(item)));
        if (inline.isEmpty()) {
            capabilities.remove(member);
        }
    }

    private static void normalizeOffering(OdpJsonNode document) {
        OdpJsonNode schema = document.get("schema");
        if (schema != null
                && schema.isObject()
                && schema.fieldNames().stream().anyMatch(name -> !FIELD_URL.equals(name))) {
            document.remove("schema");
        }
        OdpJsonNode price = document.get("price");
        Set<String> prices = Set.of("fixed", "free", "metered", "quote", "range", "starting_at");
        if (price != null && price.isObject()) {
            OdpJsonNode type = price.get(FIELD_TYPE);
            if (type != null && type.isString() && !prices.contains(type.asString())) {
                document.remove("price");
            }
        }
        OdpJsonNode actions = document.get("actions");
        if (actions == null || !actions.isArray()) {
            return;
        }
        actions.removeIf(action -> {
            if (action.isObject() && hasUnknownAuthentication(action)) {
                return true;
            }
            if (action.isObject()
                    && action.fieldNames().stream()
                            .anyMatch(name -> !Set.of("authentication", "description", "http", "id", "openapi", "rel")
                                    .contains(name))) {
                return true;
            }
            OdpJsonNode http = action.get("http");
            if (http != null
                    && http.isObject()
                    && http.fieldNames().stream()
                            .anyMatch(name -> !Set.of("href", "method", "request", "response_content_types")
                                    .contains(name))) {
                return true;
            }
            OdpJsonNode request = action.at("/http/request");
            if (request.isObject()
                    && request.fieldNames().stream()
                            .anyMatch(name -> !Set.of("content_type", "schema").contains(name))) {
                return true;
            }
            OdpJsonNode actionSchema = action.at("/http/request/schema");
            if (actionSchema.isObject()
                    && actionSchema.fieldNames().stream().anyMatch(name -> !FIELD_URL.equals(name))) {
                return true;
            }
            OdpJsonNode openapi = action.get("openapi");
            if (openapi != null
                    && openapi.isObject()
                    && openapi.fieldNames().stream()
                            .anyMatch(name -> !Set.of("operation_id", FIELD_URL).contains(name))) {
                return true;
            }
            OdpJsonNode method = action.at("/http/method");
            return method.isString() && !Set.of("GET", "POST").contains(method.asString());
        });
        if (actions.isEmpty()) {
            document.remove("actions");
        }
    }

    private static void filterDefinitions(OdpJsonNode document, boolean filters) {
        OdpJsonNode items = document.get("items");
        if (items == null || !items.isArray()) {
            return;
        }
        items.removeIf(item -> item.isObject() && !(filters ? knownFilter(item) : knownSort(item)));
    }

    private static boolean knownFilter(OdpJsonNode definition) {
        OdpJsonNode type = definition.get(FIELD_TYPE);
        if (type != null
                && type.isString()
                && !Set.of("boolean", "date", "date-time", "decimal", "integer", "number", "string")
                        .contains(type.asString())) {
            return false;
        }
        OdpJsonNode operators = definition.get("operators");
        if (operators != null && operators.isArray()) {
            for (OdpJsonNode operator : operators) {
                if (operator.isString()
                        && !Set.of("eq", "exists", "gt", "gte", "in", "lt", "lte")
                                .contains(operator.asString())) {
                    return false;
                }
            }
        }
        OdpJsonNode system = definition.at("/unit/system");
        return !system.isString() || Set.of("service", "ucum").contains(system.asString());
    }

    private static boolean knownSort(OdpJsonNode definition) {
        OdpJsonNode keys = definition.get("keys");
        if (keys == null || !keys.isArray()) {
            return true;
        }
        for (OdpJsonNode key : keys) {
            OdpJsonNode direction = key.get("direction");
            OdpJsonNode missing = key.get("missing");
            if (direction != null
                            && direction.isString()
                            && !Set.of("ascending", "descending").contains(direction.asString())
                    || missing != null
                            && missing.isString()
                            && !Set.of("first", "last").contains(missing.asString())) {
                return false;
            }
        }
        return true;
    }

    private static void filterProblemParameters(OdpJsonNode document) {
        OdpJsonNode parameters = document.get("invalid_params");
        if (parameters == null || !parameters.isArray()) {
            return;
        }
        parameters.removeIf(parameter -> {
            OdpJsonNode location = parameter.get("in");
            return location != null
                    && location.isString()
                    && !Set.of("body", "header", "path", "query").contains(location.asString());
        });
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
        OfferingPage page =
                parse(json, "offering-search-response.schema.json", "Offering search response", OfferingPage.class);
        page.items()
                .forEach(item ->
                        parseOffering(withInheritedVersion(write(item), page.odpVersion(), item.odpVersion() != null)));
        return page;
    }

    public static <T> Page<T> parsePage(String json, Class<T> itemType) {
        validate(json, "page-envelope.schema.json", "page envelope");
        Page<T> page = decodePage(json, itemType, "page envelope");
        if (itemType == Collection.class) {
            page.items().forEach(item -> {
                Collection collection = (Collection) item;
                parseCollection(
                        withInheritedVersion(write(collection), page.odpVersion(), collection.odpVersion() != null));
            });
        } else if (itemType == Offering.class) {
            page.items().forEach(item -> {
                Offering offering = (Offering) item;
                parseOffering(withInheritedVersion(write(offering), page.odpVersion(), offering.odpVersion() != null));
            });
        }
        return page;
    }

    public static String write(Object value) {
        try {
            return PROVIDER.write(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unable to encode ODP JSON", exception);
        }
    }

    public static OdpJsonSchema compileSchema(Map<String, String> schemas, String rootSchema) {
        OdpJsonSchema schema = PROVIDER.compileSchemas(schemas).get(rootSchema);
        if (schema == null) {
            throw new IllegalArgumentException("ODP JSON Schema registry does not contain " + rootSchema);
        }
        return schema;
    }

    public static OdpJsonNode parseTree(String json) {
        return PROVIDER.parseTree(json);
    }

    public static <T> T read(String json, Class<T> type) {
        return PROVIDER.decode(json, type);
    }

    public static <T> T treeToValue(OdpJsonNode node, Class<T> type) {
        return PROVIDER.treeToValue(node, type);
    }

    public static OdpJsonNode valueToTree(Object value) {
        return PROVIDER.valueToTree(value);
    }

    private static String withInheritedVersion(String json, String version, boolean present) {
        if (present) {
            return json;
        }
        return "{\"odp_version\":" + write(version) + (json.length() == 2 ? "}" : "," + json.substring(1));
    }

    private static <T> T parse(String json, String schemaName, String documentType, Class<T> type) {
        validate(json, schemaName, documentType);
        return decode(json, type, documentType);
    }

    private static void filterAgentProtocols(OdpJsonNode document) {
        OdpJsonNode value = document.get("protocols");
        if (value == null || !value.isObject()) {
            return;
        }
        filterAgentProtocolCategory(value, "enrollment", Set.of("aep"));
        filterAgentProtocolCategory(value, "payments", Set.of("mpp", "x402"));
        filterAgentProtocolCategory(value, "trust", Set.of("tap"));
        if (value.isEmpty()) {
            document.remove("protocols");
        }
    }

    private static void filterAgentProtocolCategory(OdpJsonNode protocols, String category, Set<String> recognized) {
        OdpJsonNode value = protocols.get(category);
        if (value == null || !value.isArray()) {
            return;
        }
        int originalSize = value.size();
        value.removeIf(descriptor -> {
            OdpJsonNode name = descriptor.isObject() ? descriptor.get(FIELD_NAME) : null;
            return name != null && name.isString() && !recognized.contains(name.asString());
        });
        if (originalSize != value.size() && value.isEmpty()) {
            protocols.remove(category);
        }
    }

    private static <T> T decode(String json, Class<T> type, String documentType) {
        try {
            return PROVIDER.decode(json, type);
        } catch (IllegalArgumentException exception) {
            throw new OdpValidationException(
                    documentType,
                    List.of(new ValidationIssue("json", exception.getMessage(), Map.of(), "")),
                    exception);
        }
    }

    private static <T> Page<T> decodePage(String json, Class<T> itemType, String documentType) {
        try {
            return PROVIDER.decodePage(json, itemType);
        } catch (IllegalArgumentException exception) {
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
        List<ValidationIssue> issues = SCHEMAS.get(schemaName).validate(json);
        if (!issues.isEmpty()) {
            throw new OdpValidationException(documentType, issues);
        }
    }

    private static Map<String, OdpJsonSchema> compileSchemas() {
        Map<String, OdpJsonSchema> compiled = PROVIDER.compileSchemas(SCHEMA_DOCUMENTS);
        Map<String, OdpJsonSchema> schemas = new HashMap<>();
        for (String name : SCHEMA_DOCUMENTS.keySet()) {
            schemas.put(name.substring(SCHEMA_ORIGIN.length()), compiled.get(name));
        }
        return Map.copyOf(schemas);
    }

    private static OdpJsonProvider loadProvider() {
        List<OdpJsonProvider> providers = ServiceLoader.load(OdpJsonProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.size() != REQUIRED_PROVIDER_COUNT) {
            throw new IllegalStateException(
                    "ODP JSON requires exactly one provider; add either odp-json-jackson2 or odp-json-jackson3");
        }
        return providers.get(0);
    }

    private static Map<String, String> readSchemas() {
        Map<String, String> schemas = new HashMap<>();
        for (String name :
                readSchema("index.txt").lines().filter(line -> !line.isBlank()).toList()) {
            schemas.put(SCHEMA_ORIGIN + name, readSchema(name));
        }
        return Map.copyOf(schemas);
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
