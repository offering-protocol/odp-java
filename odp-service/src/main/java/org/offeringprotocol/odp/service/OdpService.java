package org.offeringprotocol.odp.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpValidationException;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.ProblemDetails;
import org.offeringprotocol.odp.core.SearchCapabilities;
import org.offeringprotocol.odp.core.ServiceDocument;

/** Framework-neutral ODP Service request handler. */
public final class OdpService {
    private static final int MAXIMUM_REQUEST_BYTES = 65_536;
    private static final String MEDIA_TYPE = "application/odp+json";
    private static final String GET = "GET";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String NOT_FOUND = "NOT_FOUND";

    private final ServiceDocument serviceDocument;
    private final Map<OdpOperation, Endpoint> endpoints;
    private final String endpointBase;

    public static Builder builder(String name, String description, String language, String endpointBase) {
        return new Builder(name, description, language, endpointBase);
    }

    public OdpService(ServiceDocument template, Map<OdpOperation, Endpoint> endpoints) {
        Objects.requireNonNull(template, "template");
        if (!endpoints.containsKey(OdpOperation.LIST_OFFERINGS) || !endpoints.containsKey(OdpOperation.GET_OFFERING)) {
            throw new IllegalArgumentException("ODP Services require list-offerings and get-offering handlers");
        }
        this.endpoints = Map.copyOf(endpoints);
        this.endpointBase = template.http().endpointBase().replaceFirst("/$", "");
        List<OperationDescriptor> operations = endpoints.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OperationDescriptor(entry.getValue().authentication(), entry.getKey()))
                .toList();
        this.serviceDocument = template.toBuilder().operations(operations).build();
        OdpJson.parseServiceDocument(OdpJson.write(this.serviceDocument));
    }

    public ServiceDocument document() {
        return serviceDocument.toBuilder().build();
    }

    public OdpHttpResponse handle(OdpHttpRequest request) {
        try {
            if (request.body() != null
                    && request.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            > MAXIMUM_REQUEST_BYTES) {
                return problem(413, "REQUEST_TOO_LARGE", "ODP request exceeds its byte limit");
            }
            if (GET.equals(request.method()) && Odp.SERVICE_DOCUMENT_PATH.equals(request.path())) {
                return json(200, serviceDocument);
            }
            Route route = route(request);
            Endpoint endpoint = endpoints.get(route.operation());
            if (endpoint == null) {
                return problem(404, NOT_FOUND, "ODP endpoint not found");
            }
            String representation = request.queryValue("representation");
            if (representation == null) {
                representation = "terse";
            }
            if (!"terse".equals(representation) && !"full".equals(representation)) {
                return problem(400, "INVALID_REQUEST", "representation must be terse or full");
            }
            Integer limit = integerQuery(request, "limit", 1, 100);
            CatalogRequest catalogRequest = new CatalogRequest(
                    route.identifier(),
                    representation,
                    limit,
                    request.queryValue("cursor"),
                    request.headerValue("Accept-Language"),
                    request.body(),
                    request);
            Object response = endpoint.handler().handle(catalogRequest);
            if (response == null) {
                return problem(404, NOT_FOUND, "ODP resource not found");
            }
            validateResponse(route.operation(), response, representation);
            return json(200, response);
        } catch (OdpServiceException exception) {
            return problem(exception.status(), exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return problem(400, "INVALID_REQUEST", exception.getMessage());
        }
    }

    private static void validateResponse(OdpOperation operation, Object response, String representation) {
        String json = OdpJson.write(response);
        try {
            switch (operation) {
                case GET_COLLECTION -> validateCollection(OdpJson.parseCollection(json), representation);
                case GET_OFFERING -> validateOffering(OdpJson.parseOffering(json), representation);
                case LIST_COLLECTIONS, SEARCH_COLLECTIONS ->
                    OdpJson.parsePage(json, Collection.class)
                            .items()
                            .forEach(item -> validateCollection(item, representation));
                case LIST_COLLECTION_OFFERINGS, LIST_OFFERINGS ->
                    OdpJson.parsePage(json, Offering.class)
                            .items()
                            .forEach(item -> validateOffering(item, representation));
                case SEARCH_OFFERINGS ->
                    OdpJson.parseOfferingSearchResponse(json)
                            .items()
                            .forEach(item -> validateOffering(item, representation));
            }
        } catch (OdpValidationException exception) {
            throw new OdpServiceException(500, INTERNAL_ERROR, "ODP catalog returned an invalid response", exception);
        }
    }

    private static void validateOffering(Offering offering, String representation) {
        if ("terse".equals(representation) && offering.actions() != null) {
            throw new OdpServiceException(500, INTERNAL_ERROR, "ODP catalog returned Actions in a Terse Offering");
        }
        if ("full".equals(representation) && offering.detailFields() != null) {
            throw new OdpServiceException(500, INTERNAL_ERROR, "ODP catalog returned detail_fields in a Full Offering");
        }
    }

    private static void validateCollection(Collection collection, String representation) {
        if ("full".equals(representation) && collection.detailFields() != null) {
            throw new OdpServiceException(
                    500, INTERNAL_ERROR, "ODP catalog returned detail_fields in a Full Collection");
        }
    }

    private Route route(OdpHttpRequest request) {
        if (!request.path().startsWith(endpointBase + "/")) {
            throw new OdpServiceException(404, NOT_FOUND, "ODP endpoint not found");
        }
        String path = request.path().substring(endpointBase.length());
        String[] parts = path.split("/");
        if (GET.equals(request.method()) && "/collections".equals(path)) {
            return new Route(OdpOperation.LIST_COLLECTIONS, null);
        }
        if (("POST".equals(request.method()) || GET.equals(request.method())) && "/collections/search".equals(path)) {
            return new Route(OdpOperation.SEARCH_COLLECTIONS, null);
        }
        if (GET.equals(request.method()) && parts.length == 3 && "collections".equals(parts[1])) {
            return new Route(OdpOperation.GET_COLLECTION, parts[2]);
        }
        if (GET.equals(request.method())
                && parts.length == 4
                && "collections".equals(parts[1])
                && "offerings".equals(parts[3])) {
            return new Route(OdpOperation.LIST_COLLECTION_OFFERINGS, parts[2]);
        }
        if (GET.equals(request.method()) && "/offerings".equals(path)) {
            return new Route(OdpOperation.LIST_OFFERINGS, null);
        }
        if (("POST".equals(request.method()) || GET.equals(request.method())) && "/offerings/search".equals(path)) {
            return new Route(OdpOperation.SEARCH_OFFERINGS, null);
        }
        if (GET.equals(request.method()) && parts.length == 3 && "offerings".equals(parts[1])) {
            return new Route(OdpOperation.GET_OFFERING, parts[2]);
        }
        throw new OdpServiceException(404, NOT_FOUND, "ODP endpoint not found");
    }

    private static Integer integerQuery(OdpHttpRequest request, String name, int minimum, int maximum) {
        String value = request.queryValue(name);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be from " + minimum + " through " + maximum, exception);
        }
    }

    private static OdpHttpResponse json(int status, Object body) {
        return new OdpHttpResponse(status, Map.of("Content-Type", MEDIA_TYPE), OdpJson.write(body));
    }

    private static OdpHttpResponse problem(int status, String code, String detail) {
        ProblemDetails problem = new ProblemDetails(
                "https://offeringprotocol.org/problems/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                detail,
                status,
                code,
                detail,
                null,
                null,
                Map.of());
        return new OdpHttpResponse(status, Map.of("Content-Type", "application/problem+json"), OdpJson.write(problem));
    }

    public record Endpoint(AuthenticationRequirement authentication, CatalogHandler handler) {
        public Endpoint {
            Objects.requireNonNull(authentication);
            Objects.requireNonNull(handler);
        }
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final String language;
        private final String endpointBase;
        private String configuredDocumentationUrl;
        private List<String> configuredLocalizations;
        private List<ServiceDocument.McpEndpoint> configuredMcp;
        private List<String> configuredKeywords;
        private ServiceDocument.Branding configuredBranding;
        private ServiceDocument.OpenApi configuredOpenApi;
        private ServiceDocument.Protocols configuredProtocols;
        private List<String> configuredPaymentOrigins;
        private SearchCapabilities configuredSearchCapabilities;
        private String configuredStatusUrl;
        private String configuredSupportUrl;
        private String configuredWebsiteUrl;
        private Map<String, OdpJsonNode> configuredAdditional = Map.of();
        private Map<OdpOperation, Endpoint> configuredEndpoints;
        private Map<OdpOperation, AuthenticationRequirement> configuredOperationAuthentication = Map.of();

        private Builder(String name, String description, String language, String endpointBase) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.language = Objects.requireNonNull(language, "language");
            this.endpointBase = Objects.requireNonNull(endpointBase, "endpointBase");
            this.configuredLocalizations = List.of(language);
        }

        public Builder documentationUrl(String value) {
            this.configuredDocumentationUrl = value;
            return this;
        }

        public Builder localizations(List<String> values) {
            this.configuredLocalizations = List.copyOf(values);
            return this;
        }

        public Builder mcp(List<ServiceDocument.McpEndpoint> values) {
            this.configuredMcp = List.copyOf(values);
            return this;
        }

        public Builder keywords(List<String> values) {
            this.configuredKeywords = List.copyOf(values);
            return this;
        }

        public Builder branding(ServiceDocument.Branding value) {
            this.configuredBranding = value;
            return this;
        }

        public Builder openApi(ServiceDocument.OpenApi value) {
            this.configuredOpenApi = value;
            return this;
        }

        public Builder protocols(ServiceDocument.Protocols value) {
            this.configuredProtocols = value;
            return this;
        }

        public Builder paymentOrigins(List<String> values) {
            this.configuredPaymentOrigins = List.copyOf(values);
            return this;
        }

        public Builder searchCapabilities(SearchCapabilities value) {
            this.configuredSearchCapabilities = value;
            return this;
        }

        public Builder statusUrl(String value) {
            this.configuredStatusUrl = value;
            return this;
        }

        public Builder supportUrl(String value) {
            this.configuredSupportUrl = value;
            return this;
        }

        public Builder websiteUrl(String value) {
            this.configuredWebsiteUrl = value;
            return this;
        }

        public Builder additional(Map<String, OdpJsonNode> values) {
            this.configuredAdditional = Map.copyOf(values);
            return this;
        }

        public Builder endpoints(Map<OdpOperation, Endpoint> values) {
            this.configuredEndpoints = Map.copyOf(values);
            return this;
        }

        public Builder operationAuthentication(Map<OdpOperation, AuthenticationRequirement> values) {
            this.configuredOperationAuthentication = Map.copyOf(values);
            return this;
        }

        public OdpService build() {
            if (configuredEndpoints == null) {
                throw new IllegalStateException("endpoints must be configured");
            }
            ServiceDocument template = ServiceDocument.builder(
                            name, description, language, new ServiceDocument.Http(endpointBase, configuredOpenApi))
                    .documentationUrl(configuredDocumentationUrl)
                    .localizations(configuredLocalizations)
                    .mcp(configuredMcp)
                    .keywords(configuredKeywords)
                    .branding(configuredBranding)
                    .operations(List.of())
                    .protocols(configuredProtocols)
                    .paymentOrigins(configuredPaymentOrigins)
                    .searchCapabilities(configuredSearchCapabilities)
                    .statusUrl(configuredStatusUrl)
                    .supportUrl(configuredSupportUrl)
                    .websiteUrl(configuredWebsiteUrl)
                    .additional(configuredAdditional)
                    .build();
            Map<OdpOperation, Endpoint> configuredEndpoints = new EnumMap<>(OdpOperation.class);
            configuredEndpoints.putAll(this.configuredEndpoints);
            configuredOperationAuthentication.forEach((operation, requirement) -> {
                Endpoint endpoint = configuredEndpoints.get(operation);
                if (endpoint == null) {
                    throw new IllegalArgumentException(
                            "authentication requirement refers to an unconfigured operation: " + operation.value());
                }
                configuredEndpoints.put(operation, new Endpoint(requirement, endpoint.handler()));
            });
            return new OdpService(template, configuredEndpoints);
        }
    }

    private record Route(OdpOperation operation, String identifier) {}
}
