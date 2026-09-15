package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.URI;
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
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OfferingPage;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.core.ProblemDetails;
import org.offeringprotocol.odp.core.SearchRequests;

/** Validated ODP Service inspection and catalog client. */
public final class OdpServiceClient {
    /** ERR-21 and SVC-83: the byte and nesting-depth budgets each class of document is read under. */
    private static final int MAXIMUM_DOCUMENT_BYTES = 65_536;

    private static final int MAXIMUM_DOCUMENT_DEPTH = 8;
    private static final int MAXIMUM_RESOURCE_BYTES = 524_288;
    private static final int MAXIMUM_RESOURCE_DEPTH = 16;
    private static final int MAXIMUM_PROBLEM_BYTES = 16_384;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final String GET = "GET";
    private static final String MEDIA_TYPE = "application/odp+json";
    private static final String TERSE = "terse";
    private static final String FULL = "full";

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

    /**
     * A transport that resolves every destination and refuses any address the IANA special-purpose
     * registries mark as non-public (SEC-08). This is what {@link #create(URI)} uses.
     */
    public static OdpTransport defaultTransport() {
        return SecureDestinations.transport(false);
    }

    /**
     * A transport that additionally reaches a loopback Service, for running against a Service on the
     * same machine. It still refuses every other non-public address, and refuses a loopback name
     * that resolves anywhere else.
     */
    public static OdpTransport localDevelopmentTransport() {
        return SecureDestinations.transport(true);
    }

    public static OdpServiceClient create(URI serviceUri) {
        return create(serviceUri, defaultTransport());
    }

    public static OdpServiceClient create(URI serviceUri, OdpTransport transport) {
        return create(serviceUri, transport, defaultTransport());
    }

    public static OdpServiceClient create(URI serviceUri, OdpTransport transport, OdpTransport supportingTransport) {
        Objects.requireNonNull(serviceUri, "serviceUri");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(supportingTransport, "supportingTransport");
        String origin = OdpUris.deriveServiceOrigin(serviceUri);
        URI documentUri = URI.create(origin).resolve(Odp.SERVICE_DOCUMENT_PATH);
        String json = request(transport, documentUri, GET, null, null, MAXIMUM_DOCUMENT_BYTES, MAXIMUM_DOCUMENT_DEPTH);
        var document = OdpJson.parseAgentServiceDocument(json);
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
        String selected = requireRepresentation(representation);
        return collectionPage(
                requestOperation(OdpOperation.LIST_COLLECTIONS, null, selected, limit, language, null), selected);
    }

    public Page<Collection> searchCollections(
            SearchRequests.Collections request, String representation, String language) {
        String selected = requireRepresentation(representation);
        return collectionPage(
                requestOperation(
                        OdpOperation.SEARCH_COLLECTIONS, null, selected, null, language, OdpJson.write(request)),
                selected);
    }

    public Collection getCollection(String id, String representation, String language) {
        String selected = requireRepresentation(representation);
        Collection collection =
                parseAgentCollection(requestOperation(OdpOperation.GET_COLLECTION, id, selected, null, language, null));
        requireCollection(collection, selected, false);
        return collection;
    }

    public Page<Offering> listCollectionOfferings(
            String collectionId, String representation, Integer limit, String language) {
        String selected = requireRepresentation(representation);
        return offeringPage(
                requestOperation(OdpOperation.LIST_COLLECTION_OFFERINGS, collectionId, selected, limit, language, null),
                selected);
    }

    public Page<Offering> listOfferings(String representation, Integer limit, String language) {
        String selected = requireRepresentation(representation);
        return offeringPage(
                requestOperation(OdpOperation.LIST_OFFERINGS, null, selected, limit, language, null), selected);
    }

    public OfferingPage searchOfferings(SearchRequests.Offerings request, String representation, String language) {
        String selected = requireRepresentation(representation);
        OfferingPage page = parseAgentOfferingSearchResponse(requestOperation(
                OdpOperation.SEARCH_OFFERINGS, null, selected, null, language, OdpJson.write(request)));
        page.items().forEach(item -> requireOffering(item, selected, true));
        return page;
    }

    public Offering getOffering(String id, String representation, String language) {
        String selected = requireRepresentation(representation);
        Offering offering =
                parseAgentOffering(requestOperation(OdpOperation.GET_OFFERING, id, selected, null, language, null));
        requireOffering(offering, selected, false);
        return offering;
    }

    public OfferingDetails getOfferingDetails(String id, String language) {
        Offering offering = getOffering(id, FULL, language);
        String serviceOpenApiUrl = serviceInspection.document().http().openapi() == null
                ? null
                : serviceInspection.document().http().openapi().url();
        ActionResolver.NormalizedActions normalized =
                actionResolver.normalize(offering.actions(), serviceOrigin, serviceOpenApiUrl);
        List<OfferingIssue> issues = new ArrayList<>(normalized.issues());
        Offering safeOffering = offering;
        OdpJsonNode attributeSchema = null;
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
        return collectionPage(
                request(transport, target, GET, null, language, MAXIMUM_RESOURCE_BYTES, MAXIMUM_RESOURCE_DEPTH), null);
    }

    public Page<Offering> continueOfferings(String next, String language) {
        URI target = OdpUris.resolveContinuation(next, serviceOrigin);
        return offeringPage(
                request(transport, target, GET, null, language, MAXIMUM_RESOURCE_BYTES, MAXIMUM_RESOURCE_DEPTH), null);
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
        if (limit != null && (limit < 1 || limit > 100)) {
            throw new IllegalArgumentException("limit must be from 1 through 100");
        }
        URI target = OdpUris.buildOperationUri(
                serviceInspection.document().http().endpointBase(), operation, serviceOrigin, identifier);
        String separator = target.getQuery() == null ? "?" : "&";
        target = URI.create(
                target + separator + "representation=" + representation + (limit == null ? "" : "&limit=" + limit));
        return request(
                transport, target, operation.method(), body, language, MAXIMUM_RESOURCE_BYTES, MAXIMUM_RESOURCE_DEPTH);
    }

    /** SVC-72: a request carries exactly one representation, and only a value ODP defines. */
    private static String requireRepresentation(String representation) {
        String selected = representation == null ? TERSE : representation;
        if (!TERSE.equals(selected) && !FULL.equals(selected)) {
            throw new IllegalArgumentException("representation must be terse or full");
        }
        return selected;
    }

    private static Page<Collection> collectionPage(String json, String representation) {
        Page<Collection> page = parseAgentCollectionPage(json);
        page.items().forEach(item -> requireCollection(item, representation, true));
        return page;
    }

    private static Page<Offering> offeringPage(String json, String representation) {
        Page<Offering> page = parseAgentOfferingPage(json);
        page.items().forEach(item -> requireOffering(item, representation, true));
        return page;
    }

    private static void requireOffering(Offering offering, String representation, boolean nested) {
        requireSummary(offering.id(), offering.name(), "Offering");
        requireNestedVersion(offering.odpVersion(), nested, "Offering");
        if (TERSE.equals(representation)
                && offering.actions() != null
                && !offering.actions().isEmpty()) {
            throw new IllegalArgumentException("ODP Terse Offering cannot contain Actions");
        }
        requireDetailFields(offering.detailFields(), representation, "Offering");
    }

    private static void requireCollection(Collection collection, String representation, boolean nested) {
        requireSummary(collection.id(), collection.name(), "Collection");
        requireNestedVersion(collection.odpVersion(), nested, "Collection");
        requireDetailFields(collection.detailFields(), representation, "Collection");
    }

    private static void requireDetailFields(List<String> detailFields, String representation, String resourceType) {
        if (FULL.equals(representation) && detailFields != null && !detailFields.isEmpty()) {
            throw new IllegalArgumentException("ODP Full " + resourceType + " cannot contain detail_fields");
        }
    }

    /** VER-03: an item nested in a page inherits its container's version and cannot restate it. */
    private static void requireNestedVersion(String odpVersion, boolean nested, String resourceType) {
        if (nested && odpVersion != null) {
            throw new IllegalArgumentException("ODP page item " + resourceType + " cannot restate odp_version");
        }
    }

    private static void requireSummary(String identifier, String name, String resourceType) {
        if (!OdpUris.isLocalResourceIdentifier(identifier) || name == null || name.isBlank()) {
            throw new IllegalArgumentException(resourceType + " summary is invalid");
        }
    }

    private static Offering withoutAttributes(Offering offering) {
        OdpJsonNode document = OdpJson.valueToTree(offering);
        document.remove("attributes");
        return parseAgentOffering(document.toString());
    }

    private static Collection parseAgentCollection(String json) {
        return OdpJson.parseCollection(OdpJson.normalizeAgentResponse(json, "collection"));
    }

    private static Offering parseAgentOffering(String json) {
        return OdpJson.parseOffering(OdpJson.normalizeAgentResponse(json, "offering"));
    }

    private static Page<Collection> parseAgentCollectionPage(String json) {
        return OdpJson.parsePage(OdpJson.normalizeAgentResponse(json, "collection-page"), Collection.class);
    }

    private static Page<Offering> parseAgentOfferingPage(String json) {
        return OdpJson.parsePage(OdpJson.normalizeAgentResponse(json, "offering-page"), Offering.class);
    }

    private static OfferingPage parseAgentOfferingSearchResponse(String json) {
        return OdpJson.parseOfferingSearchResponse(OdpJson.normalizeAgentResponse(json, "offering-page"));
    }

    private static String request(
            OdpTransport transport,
            URI target,
            String method,
            String body,
            String language,
            int maximumBytes,
            int maximumDepth) {
        URI current = target;
        String currentMethod = method;
        String currentBody = body;
        boolean hasBody = body != null;
        for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", MEDIA_TYPE + ", application/problem+json");
            if (language != null && !language.isBlank()) {
                builder.header("Accept-Language", language);
            }
            if (!hasBody) {
                builder.method(currentMethod, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", MEDIA_TYPE)
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
                boolean failure = status < 200 || status > 299;
                int limit = failure ? MAXIMUM_PROBLEM_BYTES : maximumBytes;
                // ERR-20: a declared length past the limit is refused before the body is read, and
                // what did arrive is measured before it is decoded.
                requireDeclaredLength(response, limit);
                byte[] bytes = response.body();
                if (bytes.length > limit) {
                    throw new IllegalStateException("ODP response exceeds its byte limit");
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (failure) {
                    throw failureFor(status, text, response);
                }
                requireMediaType(response);
                requireDepth(text, maximumDepth);
                return text;
            }
        }
        throw new IllegalStateException("ODP request produced no response");
    }

    private static void requireDeclaredLength(HttpResponse<byte[]> response, int limit) {
        response.headers().firstValueAsLong("Content-Length").ifPresent(declared -> {
            if (declared > limit) {
                throw new IllegalStateException("ODP response exceeds its byte limit");
            }
        });
    }

    /** MED-08 and MED-09: the media-type essence is compared whole, and case-insensitively. */
    private static void requireMediaType(HttpResponse<byte[]> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String essence = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPE.equals(essence)) {
            throw new IllegalStateException("ODP response must use " + MEDIA_TYPE);
        }
    }

    /** ERR-18 and ERR-21: nesting is bounded so a document cannot be read deeper than the protocol allows. */
    private static void requireDepth(String json, int maximumDepth) {
        if (depth(OdpJson.parseTree(json), maximumDepth) > maximumDepth) {
            throw new IllegalStateException("ODP response exceeds its nesting-depth limit");
        }
    }

    private static int depth(OdpJsonNode value, int remaining) {
        if (remaining <= 0) {
            return 1;
        }
        int maximum = 1;
        for (OdpJsonNode child : value) {
            maximum = Math.max(maximum, 1 + depth(child, remaining - 1));
        }
        return maximum;
    }

    /**
     * The Problem Details a failed response carried, when it describes the failure that occurred. A
     * document whose stated status disagrees with the response is describing a different one, so the
     * HTTP status stands alone instead.
     */
    private static ProblemDetails problemFor(int status, String text) {
        try {
            ProblemDetails parsed = OdpJson.parseProblemDetails(OdpJson.normalizeAgentResponse(text, "problem"));
            if (parsed.status() == status) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // The HTTP status remains available when a peer does not return ODP Problem Details.
        }
        return null;
    }

    private static OdpRequestException failureFor(int status, String text, HttpResponse<byte[]> response) {
        ProblemDetails problem = problemFor(status, text);
        return new OdpRequestException(
                status,
                problem == null ? "ODP request failed with HTTP " + status : problem.title(),
                response.headers(),
                problem);
    }
}
