package org.offeringprotocol.odp.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.ProblemDetails;
import org.offeringprotocol.odp.core.ServiceDocument;

/** Framework-neutral ODP Service request handler. */
public final class OdpService {
    private static final String MEDIA_TYPE = "application/odp+json";
    private static final String GET = "GET";
    private static final String NOT_FOUND = "NOT_FOUND";

    private final ServiceDocument serviceDocument;
    private final Map<OdpOperation, Endpoint> endpoints;
    private final String endpointBase;

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
        this.serviceDocument = new ServiceDocument(
                template.odpVersion(),
                template.name(),
                template.description(),
                template.documentationUrl(),
                template.language(),
                template.localizations(),
                template.mcp(),
                template.keywords(),
                template.branding(),
                operations,
                template.http(),
                template.protocols(),
                template.paymentOrigins(),
                template.searchCapabilities(),
                template.statusUrl(),
                template.supportUrl(),
                template.websiteUrl(),
                template.additional());
        OdpJson.parseServiceDocument(OdpJson.write(this.serviceDocument));
    }

    public ServiceDocument document() {
        return serviceDocument;
    }

    public OdpHttpResponse handle(OdpHttpRequest request) {
        try {
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
            return json(200, response);
        } catch (OdpServiceException exception) {
            return problem(exception.status(), exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return problem(400, "INVALID_REQUEST", exception.getMessage());
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
        if ("POST".equals(request.method()) && "/collections/search".equals(path)) {
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
        if ("POST".equals(request.method()) && "/offerings/search".equals(path)) {
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

    private record Route(OdpOperation operation, String identifier) {}
}
