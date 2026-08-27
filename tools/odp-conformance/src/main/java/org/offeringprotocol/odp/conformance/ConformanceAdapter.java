package org.offeringprotocol.odp.conformance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;
import org.offeringprotocol.odp.agent.OdpServiceClient;
import org.offeringprotocol.odp.agent.OdpTransport;
import org.offeringprotocol.odp.agent.OfferingDetails;
import org.offeringprotocol.odp.agent.OfferingIssue;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ResourceIdentity;
import org.offeringprotocol.odp.core.ServiceDocument;
import org.offeringprotocol.odp.service.OdpHttpRequest;
import org.offeringprotocol.odp.service.OdpService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Process adapter for the language-neutral ODP conformance harness. */
public final class ConformanceAdapter {
    private static final int MAXIMUM_MESSAGE_LENGTH = 1024;
    private static final String AGENT_ROLE = "agent";
    private static final String DOCUMENT_FIELD = "document";
    private static final String ROOT_SCHEMA_URL = "https://schemas.example/root.json";
    private static final String VALIDATE_PROBLEM = "validate-problem";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> AGENT_BASELINE = Set.of(
            "enforce-compatibility",
            "enforce-redirect-and-security",
            "follow-pagination",
            "get-offering",
            "handle-errors-and-limits",
            "honor-caching",
            "inspect-service",
            "list-offerings",
            "process-localization",
            "process-representations");

    private ConformanceAdapter() {}

    public static void main(String[] arguments) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                if (!line.isBlank()) {
                    System.out.println( // NOPMD - Standard output is the adapter protocol channel.
                            JSON.writeValueAsString(evaluate(JSON.readTree(line))));
                }
                line = reader.readLine();
            }
        }
    }

    private static Map<String, Object> evaluate(JsonNode request) {
        int sequence = required(request, "sequence").asInt();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocol_version", "1");
        response.put("sequence", sequence);
        try {
            Evaluation evaluation = evaluateCase(
                    text(required(request, "vector"), "subject"), required(request, "case"), text(request, "role"));
            response.put("status", evaluation.status());
            if (evaluation.message() != null) {
                response.put("message", evaluation.message());
            }
        } catch (RuntimeException exception) {
            response.put("status", "failed");
            response.put("message", truncate(exception.getMessage()));
        }
        return response;
    }

    private static Evaluation evaluateCase(String subject, JsonNode test, String role) {
        return switch (subject) {
            case "local-identifier" -> result(OdpUris.isLocalResourceIdentifier(text(test, "value")) == valid(test));
            case "identity-comparison" -> evaluateIdentity(test);
            case "service-origin" -> evaluateServiceOrigin(test);
            case "resource-reference" -> evaluateReference(test);
            case "service-document" -> parse(test, DOCUMENT_FIELD, OdpJson::parseServiceDocument);
            case "collection-envelope" -> parse(test, DOCUMENT_FIELD, OdpJson::parseCollection);
            case "offering-contract" ->
                "full".equals(optionalText(test, "representation"))
                        ? parse(test, DOCUMENT_FIELD, OdpJson::parseOffering)
                        : skipped();
            case "collection-search-contract" ->
                "validate-request".equals(operation(test))
                        ? parse(test, "request", OdpJson::parseCollectionSearchRequest)
                        : skipped();
            case "offering-search-contract" ->
                "validate-request".equals(operation(test))
                        ? parse(test, "request", OdpJson::parseOfferingSearchRequest)
                        : skipped();
            case "attribute-schema-retrieval" -> evaluateAttributeSchema(test);
            case "pagination-contract" -> evaluatePagination(test);
            case "errors-limits-contract" -> evaluateErrorsAndLimits(test);
            case "role-baseline" -> evaluateBaseline(test, role);
            default -> skipped();
        };
    }

    private static Evaluation evaluateAttributeSchema(JsonNode test) {
        return switch (operation(test)) {
            case "validate-reference" -> {
                String offering = ("{\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\"," + "\"schema\":%s}")
                        .formatted(required(test, "reference"));
                yield parseValue(offering, OdpJson::parseOffering, valid(test));
            }
            case "validate-response" -> {
                var details = attributeSchemaDetails(
                        Map.of(
                                ROOT_SCHEMA_URL,
                                new SchemaResponse(
                                        required(test, DOCUMENT_FIELD).toString(),
                                        text(test, "content_type"),
                                        required(test, "status").asInt())),
                        ROOT_SCHEMA_URL,
                        "{\"name\":\"root\"}",
                        false);
                yield result((details.details().attributeSchema() != null) == valid(test));
            }
            case "validate-schema-reference-profile" -> {
                Map<String, SchemaResponse> documents = new LinkedHashMap<>();
                String rootUrl = null;
                int index = 0;
                for (JsonNode document : required(test, "documents")) {
                    String url = document.has("$id")
                            ? text(document, "$id")
                            : "https://schemas.example/document-" + index + ".json";
                    if (rootUrl == null) {
                        rootUrl = url;
                    }
                    documents.put(url, new SchemaResponse(document.toString(), "application/schema+json", 200));
                    index++;
                }
                var details = attributeSchemaDetails(
                        documents, rootUrl, "{\"children\":[{\"name\":\"child\"}],\"name\":\"root\"}", false);
                yield result((details.details().attributeSchema() != null) == valid(test));
            }
            case "validation-scope" -> {
                boolean terse = "terse".equals(text(test, "representation"));
                var details = attributeSchemaDetails(
                        Map.of(ROOT_SCHEMA_URL, new SchemaResponse("""
                                        {"$schema":"https://json-schema.org/draft/2020-12/schema",
                                        "properties":{"memory":{"type":"number"}},"type":"object"}
                                        """, "application/schema+json", 200)),
                        ROOT_SCHEMA_URL,
                        "{\"memory\":\"invalid\"}",
                        terse);
                boolean complete = details.supportingRequests() > 0
                        && details.details().offering().attributes() == null
                        && details.details().issues().stream()
                                .anyMatch(issue -> issue.scope() == OfferingIssue.Scope.ATTRIBUTES);
                yield result(complete
                        == required(test, "complete_instance_validation").asBoolean());
            }
            case "failure-scope" -> {
                var details = attributeSchemaDetails(
                        Map.of(
                                ROOT_SCHEMA_URL,
                                new SchemaResponse("{\"title\":\"Unavailable\"}", "application/problem+json", 503)),
                        ROOT_SCHEMA_URL,
                        "{\"name\":\"root\"}",
                        false);
                Map<String, Boolean> actual = Map.of(
                        "offering_usable",
                                "item".equals(details.details().offering().id()),
                        "attributes_usable", details.details().offering().attributes() != null,
                        "report_issue",
                                details.details().issues().stream()
                                        .anyMatch(issue -> issue.scope() == OfferingIssue.Scope.ATTRIBUTE_SCHEMA));
                Map<String, Boolean> expected = new LinkedHashMap<>();
                required(test, "expected")
                        .properties()
                        .forEach(entry ->
                                expected.put(entry.getKey(), entry.getValue().asBoolean()));
                yield result(actual.equals(expected));
            }
            default -> skipped();
        };
    }

    private static AttributeSchemaEvaluation attributeSchemaDetails(
            Map<String, SchemaResponse> documents, String rootUrl, String attributes, boolean terse) {
        String serviceDocument = """
                {"description":"ODP Java conformance adapter","http":{"endpoint_base":"/odp"},
                "language":"en","localizations":["en"],"name":"Conformance Service",
                "odp_version":"1.0","operations":[{"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}]}
                """;
        String offering = ("{\"attributes\":%s,\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\","
                        + "\"schema\":{\"url\":\"%s\"}}")
                .formatted(attributes, rootUrl);
        OdpTransport service = request -> response(
                request,
                "/.well-known/odp".equals(request.uri().getPath())
                        ? serviceDocument
                        : terse ? "{\"id\":\"item\",\"name\":\"Item\",\"odp_version\":\"1.0\"}" : offering,
                "application/odp+json",
                200);
        AtomicInteger supportingRequests = new AtomicInteger();
        OdpTransport supporting = request -> {
            supportingRequests.incrementAndGet();
            SchemaResponse document = documents.getOrDefault(
                    request.uri().toString(),
                    new SchemaResponse("{\"title\":\"Not Found\"}", "application/problem+json", 404));
            return response(request, document.body(), document.contentType(), document.status());
        };
        OdpServiceClient client = OdpServiceClient.create(URI.create("https://service.example"), service, supporting);
        OfferingDetails details = terse
                ? new OfferingDetails(client.getOffering("item", "terse", null), null, List.of(), List.of())
                : client.getOfferingDetails("item", null);
        return new AttributeSchemaEvaluation(details, supportingRequests.get());
    }

    private static HttpResponse<byte[]> response(HttpRequest request, String body, String contentType, int status) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("Content-Type", List.of(contentType)), (name, value) -> true);
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static Evaluation parseValue(String value, Parser parser, boolean expected) {
        boolean actual;
        try {
            parser.parse(value);
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == expected);
    }

    private record AttributeSchemaEvaluation(OfferingDetails details, int supportingRequests) {}

    private record SchemaResponse(String body, String contentType, int status) {}

    private static Evaluation evaluateIdentity(JsonNode test) {
        ResourceIdentity left = decode(required(test, "left"), ResourceIdentity.class);
        ResourceIdentity right = decode(required(test, "right"), ResourceIdentity.class);
        return result(left.equals(right) == required(test, "same_identity").asBoolean());
    }

    private static Evaluation evaluateServiceOrigin(JsonNode test) {
        boolean actual;
        String value = text(test, "value");
        try {
            actual = OdpUris.deriveServiceOrigin(URI.create(value)).equals(value);
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static Evaluation evaluateReference(JsonNode test) {
        boolean actual;
        try {
            OdpUris.resolveResourceReference(text(test, "value"), "https://service.example");
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static Evaluation evaluatePagination(JsonNode test) {
        return switch (operation(test)) {
            case "validate-page" -> parse(test, "page", value -> OdpJson.parsePage(value, JsonNode.class));
            case "validate-limit" -> {
                int limit = required(test, "limit").asInt();
                yield result((limit >= 1 && limit <= 100) == valid(test));
            }
            case "validate-next" -> {
                boolean actual;
                try {
                    OdpUris.resolveContinuation(text(test, "next"), text(test, "service_origin"));
                    actual = true;
                } catch (IllegalArgumentException exception) {
                    actual = false;
                }
                yield result(actual == valid(test));
            }
            default -> skipped();
        };
    }

    private static Evaluation evaluateErrorsAndLimits(JsonNode test) {
        if (VALIDATE_PROBLEM.equals(operation(test))) {
            boolean actual;
            try {
                var problem =
                        OdpJson.parseProblemDetails(required(test, "problem").toString());
                actual = problem.status() == required(test, "http_status").asInt();
            } catch (IllegalArgumentException exception) {
                actual = false;
            }
            return result(actual == valid(test));
        }
        if (!"validate-limit".equals(operation(test)) || !"request".equals(optionalText(test, "resource"))) {
            return skipped();
        }
        return result((serviceRequestStatus(required(test, "bytes").asInt()) == 200) == valid(test));
    }

    private static int serviceRequestStatus(int byteCount) {
        Map<OdpOperation, OdpService.Endpoint> endpoints = new EnumMap<>(OdpOperation.class);
        endpoints.put(
                OdpOperation.LIST_OFFERINGS,
                new OdpService.Endpoint(
                        AuthenticationRequirement.NOT_REQUIRED,
                        request -> new Page<Offering>(null, Odp.VERSION, List.of(), null, Map.of())));
        endpoints.put(
                OdpOperation.GET_OFFERING,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> null));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(
                        AuthenticationRequirement.NOT_REQUIRED,
                        request -> new Page<Offering>(null, Odp.VERSION, List.of(), null, Map.of())));
        OdpService service = new OdpService(document(List.of()), endpoints);
        String prefix = "{\"odp_version\":\"1.0\",\"query\":\"gpu\"}";
        String body = prefix + " ".repeat(Math.max(0, byteCount - prefix.length()));
        return service.handle(new OdpHttpRequest(
                        "POST",
                        "/odp/offerings/search",
                        Map.of(),
                        Map.of("Content-Type", List.of("application/odp+json")),
                        body))
                .status();
    }

    private static Evaluation evaluateBaseline(JsonNode test, String role) {
        if (!text(test, "role").equals(role)) {
            return skipped();
        }
        if (AGENT_ROLE.equals(role)) {
            Set<String> behaviors = new java.util.HashSet<>();
            required(test, "behaviors").forEach(value -> behaviors.add(textValue(value)));
            return result(behaviors.containsAll(AGENT_BASELINE) == valid(test));
        }
        List<OperationDescriptor> operations = new ArrayList<>();
        required(test, "operations")
                .forEach(value -> operations.add(new OperationDescriptor(
                        AuthenticationRequirement.NOT_REQUIRED, OdpOperation.fromValue(textValue(value)))));
        boolean actual;
        try {
            OdpJson.parseServiceDocument(OdpJson.write(document(operations)));
            OdpJson.parsePage(required(test, "list_response").toString(), Offering.class);
            OdpJson.parseOffering(required(test, "get_response").toString());
            actual = operations.stream()
                    .map(OperationDescriptor::name)
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(Set.of(OdpOperation.LIST_OFFERINGS, OdpOperation.GET_OFFERING));
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static ServiceDocument document(List<OperationDescriptor> operations) {
        return ServiceDocument.builder(
                        "Conformance Service", "ODP conformance Service", "en", new ServiceDocument.Http("/odp", null))
                .operations(operations)
                .build();
    }

    private static Evaluation parse(JsonNode test, String field, Parser parser) {
        boolean actual;
        try {
            parser.parse(required(test, field).toString());
            actual = true;
        } catch (IllegalArgumentException exception) {
            actual = false;
        }
        return result(actual == valid(test));
    }

    private static <T> T decode(JsonNode value, Class<T> type) {
        try {
            return JSON.readValue(value.toString(), type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Conformance case has an invalid field", exception);
        }
    }

    private static JsonNode required(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Conformance case omitted " + name);
        }
        return value;
    }

    private static String text(JsonNode object, String name) {
        return textValue(required(object, name));
    }

    private static String optionalText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value == null ? null : textValue(value);
    }

    private static String textValue(JsonNode value) {
        String result = value.stringValue();
        if (result == null) {
            throw new IllegalArgumentException("Conformance case field is not a string");
        }
        return result;
    }

    private static String operation(JsonNode test) {
        return optionalText(test, "operation");
    }

    private static boolean valid(JsonNode test) {
        return required(test, "valid").asBoolean();
    }

    private static Evaluation result(boolean matches) {
        return matches
                ? new Evaluation("passed", null)
                : new Evaluation("failed", "Public Java API result did not match the vector");
    }

    private static Evaluation skipped() {
        return new Evaluation("skipped", "No public Java operation maps this vector case");
    }

    private static String truncate(String value) {
        String message = value == null ? "Conformance evaluation failed" : value;
        return message.length() <= MAXIMUM_MESSAGE_LENGTH ? message : message.substring(0, MAXIMUM_MESSAGE_LENGTH);
    }

    @FunctionalInterface
    private interface Parser {
        Object parse(String value);
    }

    private record Evaluation(String status, String message) {}
}
