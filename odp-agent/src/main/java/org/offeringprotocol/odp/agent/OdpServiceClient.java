package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OfferingPage;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ProblemDetails;
import org.offeringprotocol.odp.core.SearchRequests;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Validated ODP Service inspection and catalog client. */
public final class OdpServiceClient {
    private static final int MAXIMUM_BYTES = 2_097_152;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final String GET = "GET";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final OdpTransport transport;
    private final ActionResolver actionResolver;
    private final AttributeSchemaResolver schemaResolver;
    private final String serviceOrigin;
    private final ServiceInspection serviceInspection;

    private OdpServiceClient(
            OdpTransport transport,
            ActionResolver actionResolver,
            AttributeSchemaResolver schemaResolver,
            String serviceOrigin,
            ServiceInspection inspection) {
        this.transport = transport;
        this.actionResolver = actionResolver;
        this.schemaResolver = schemaResolver;
        this.serviceOrigin = serviceOrigin;
        this.serviceInspection = inspection;
    }

    public static OdpServiceClient create(URI serviceUri) {
        return create(
                serviceUri, request -> DEFAULT_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    public static OdpServiceClient create(URI serviceUri, OdpTransport transport) {
        return create(
                serviceUri,
                transport,
                request -> DEFAULT_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    public static OdpServiceClient create(URI serviceUri, OdpTransport transport, OdpTransport supportingTransport) {
        Objects.requireNonNull(serviceUri, "serviceUri");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(supportingTransport, "supportingTransport");
        String origin = OdpUris.deriveServiceOrigin(serviceUri);
        URI documentUri = URI.create(origin).resolve(Odp.SERVICE_DOCUMENT_PATH);
        String json = request(transport, documentUri, GET, null, null, 524_288);
        var document = OdpJson.parseServiceDocument(json);
        Map<OdpOperation, OperationDescriptor> operations = new LinkedHashMap<>();
        for (OperationDescriptor operation : document.operations()) {
            operations.put(operation.name(), operation);
        }
        SupportingJsonClient supportingClient = new SupportingJsonClient(supportingTransport);
        AttributeSchemaResolver schemaResolver = new AttributeSchemaResolver(supportingClient);
        return new OdpServiceClient(
                transport,
                new ActionResolver(supportingClient, schemaResolver),
                schemaResolver,
                origin,
                new ServiceInspection(origin, documentUri, document, Map.copyOf(operations)));
    }

    public ServiceInspection inspection() {
        return serviceInspection;
    }

    public Page<Collection> listCollections(String representation, Integer limit, String language) {
        return collectionPage(
                requestOperation(OdpOperation.LIST_COLLECTIONS, null, representation, limit, language, null));
    }

    public Page<Collection> searchCollections(
            SearchRequests.Collections request, String representation, String language) {
        return collectionPage(requestOperation(
                OdpOperation.SEARCH_COLLECTIONS, null, representation, null, language, OdpJson.write(request)));
    }

    public Collection getCollection(String id, String representation, String language) {
        return OdpJson.parseCollection(
                requestOperation(OdpOperation.GET_COLLECTION, id, representation, null, language, null));
    }

    public Page<Offering> listCollectionOfferings(
            String collectionId, String representation, Integer limit, String language) {
        return offeringPage(requestOperation(
                OdpOperation.LIST_COLLECTION_OFFERINGS, collectionId, representation, limit, language, null));
    }

    public Page<Offering> listOfferings(String representation, Integer limit, String language) {
        return offeringPage(requestOperation(OdpOperation.LIST_OFFERINGS, null, representation, limit, language, null));
    }

    public OfferingPage searchOfferings(SearchRequests.Offerings request, String representation, String language) {
        OfferingPage page = OdpJson.parseOfferingSearchResponse(requestOperation(
                OdpOperation.SEARCH_OFFERINGS, null, representation, null, language, OdpJson.write(request)));
        page.items().forEach(item -> requireSummary(item.id(), item.name(), "Offering"));
        return page;
    }

    public Offering getOffering(String id, String representation, String language) {
        return OdpJson.parseOffering(
                requestOperation(OdpOperation.GET_OFFERING, id, representation, null, language, null));
    }

    public OfferingDetails getOfferingDetails(String id, String language) {
        Offering offering = getOffering(id, "full", language);
        String serviceOpenApiUrl = serviceInspection.document().http().openapi() == null
                ? null
                : serviceInspection.document().http().openapi().url();
        ActionResolver.NormalizedActions normalized =
                actionResolver.normalize(offering.actions(), serviceOrigin, serviceOpenApiUrl);
        List<OfferingIssue> issues = new ArrayList<>(normalized.issues());
        Offering safeOffering = offering;
        tools.jackson.databind.JsonNode attributeSchema = null;
        if (offering.schema() != null) {
            try {
                URI reference =
                        OdpUris.resolveResourceReference(offering.schema().url(), serviceOrigin);
                AttributeSchemaResolver.ResolvedSchema resolved = schemaResolver.resolve(reference);
                attributeSchema = resolved.document();
                if (offering.attributes() != null && !resolved.validates(offering.attributes())) {
                    safeOffering = withoutAttributes(offering);
                    issues.add(new OfferingIssue(
                            OfferingIssue.Scope.ATTRIBUTES,
                            "Offering attributes do not match their Attribute Schema",
                            null));
                }
            } catch (IllegalArgumentException | IllegalStateException exception) {
                safeOffering = withoutAttributes(offering);
                issues.add(new OfferingIssue(OfferingIssue.Scope.ATTRIBUTE_SCHEMA, exception.getMessage(), null));
            }
        }
        return new OfferingDetails(safeOffering, attributeSchema, normalized.actions(), issues);
    }

    public ResolvedAction resolveAction(String offeringId, String actionId, String language) {
        OfferingDetails details = getOfferingDetails(offeringId, language);
        DiscoveredAction action = details.actions().stream()
                .filter(candidate -> candidate.id().equals(actionId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("ODP Offering does not expose usable Action " + actionId));
        return actionResolver.resolve(action, serviceOrigin);
    }

    public Page<Collection> continueCollections(String next, String language) {
        URI target = OdpUris.resolveContinuation(next, serviceOrigin);
        return collectionPage(request(transport, target, GET, null, language, MAXIMUM_BYTES));
    }

    public Page<Offering> continueOfferings(String next, String language) {
        URI target = OdpUris.resolveContinuation(next, serviceOrigin);
        return offeringPage(request(transport, target, GET, null, language, MAXIMUM_BYTES));
    }

    private String requestOperation(
            OdpOperation operation,
            String identifier,
            String representation,
            Integer limit,
            String language,
            String body) {
        if (!serviceInspection.supports(operation)) {
            throw new IllegalStateException("Service does not advertise " + operation.value());
        }
        String selectedRepresentation = representation == null ? "terse" : representation;
        if (!"terse".equals(selectedRepresentation) && !"full".equals(selectedRepresentation)) {
            throw new IllegalArgumentException("representation must be terse or full");
        }
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new IllegalArgumentException("limit must be from 1 through 100");
        }
        URI target = OdpUris.buildOperationUri(
                serviceInspection.document().http().endpointBase(), operation, serviceOrigin, identifier);
        String separator = target.getQuery() == null ? "?" : "&";
        target = URI.create(target + separator + "representation=" + selectedRepresentation
                + (limit == null ? "" : "&limit=" + limit));
        return request(transport, target, operation.method(), body, language, MAXIMUM_BYTES);
    }

    private static Page<Collection> collectionPage(String json) {
        Page<Collection> page = OdpJson.parsePage(json, Collection.class);
        page.items().forEach(item -> requireSummary(item.id(), item.name(), "Collection"));
        return page;
    }

    private static Page<Offering> offeringPage(String json) {
        Page<Offering> page = OdpJson.parsePage(json, Offering.class);
        page.items().forEach(item -> requireSummary(item.id(), item.name(), "Offering"));
        return page;
    }

    private static void requireSummary(String identifier, String name, String resourceType) {
        if (!OdpUris.isLocalResourceIdentifier(identifier) || name == null || name.isBlank()) {
            throw new IllegalArgumentException(resourceType + " summary is invalid");
        }
    }

    private static Offering withoutAttributes(Offering offering) {
        try {
            var document = JSON.readTree(OdpJson.write(offering)).asObject();
            document.remove("attributes");
            return OdpJson.parseOffering(document.toString());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to normalize ODP Offering", exception);
        }
    }

    private static String request(
            OdpTransport transport, URI target, String method, String body, String language, int maximumBytes) {
        URI current = target;
        String currentMethod = method;
        String currentBody = body;
        boolean hasBody = body != null;
        for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/odp+json, application/problem+json");
            if (language != null && !language.isBlank()) {
                builder.header("Accept-Language", language);
            }
            if (!hasBody) {
                builder.method(currentMethod, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/odp+json")
                        .method(currentMethod, HttpRequest.BodyPublishers.ofString(currentBody));
            }
            HttpResponse<byte[]> response;
            try {
                response = transport.send(builder.build());
            } catch (IOException exception) {
                throw new IllegalStateException("ODP request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ODP request was interrupted", exception);
            }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (redirects == MAXIMUM_REDIRECTS) {
                    throw new IllegalStateException("ODP response exceeded its redirect limit");
                }
                String location = response.headers()
                        .firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("ODP redirect omitted Location"));
                URI next = current.resolve(location);
                if (!OdpUris.deriveServiceOrigin(next).equals(OdpUris.deriveServiceOrigin(target))) {
                    throw new IllegalStateException("ODP redirect changed Service origin");
                }
                if (status == 303 || ((status == 301 || status == 302) && "POST".equals(currentMethod))) {
                    currentMethod = GET;
                    hasBody = false;
                }
                current = next;
            } else {
                byte[] bytes = response.body();
                if (bytes.length > maximumBytes) {
                    throw new IllegalStateException("ODP response exceeds its byte limit");
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (status < 200 || status > 299) {
                    ProblemDetails problem = null;
                    try {
                        problem = OdpJson.parseProblemDetails(text);
                    } catch (IllegalArgumentException ignored) {
                        // The HTTP status remains available when a peer does not return ODP Problem Details.
                    }
                    throw new OdpRequestException(
                            status,
                            problem == null ? "ODP request failed with HTTP " + status : problem.title(),
                            response.headers(),
                            problem);
                }
                String contentType =
                        response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/odp+json")) {
                    throw new IllegalStateException("ODP response must use application/odp+json");
                }
                return text;
            }
        }
        throw new IllegalStateException("ODP request produced no response");
    }
}
