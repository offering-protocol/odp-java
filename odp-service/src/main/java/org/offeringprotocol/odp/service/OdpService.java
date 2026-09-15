package org.offeringprotocol.odp.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.OdpUris;
import org.offeringprotocol.odp.core.OdpValidationException;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.OperationDescriptor;
import org.offeringprotocol.odp.core.ProblemDetails;
import org.offeringprotocol.odp.core.SearchCapabilities;
import org.offeringprotocol.odp.core.ServiceDocument;

/** Framework-neutral ODP Service request handler. */
public final class OdpService {
    private static final int MAXIMUM_REQUEST_BYTES = 65_536;
    private static final int MAXIMUM_RESPONSE_BYTES = 524_288;
    private static final int MAXIMUM_DOCUMENT_BYTES = 65_536;
    private static final int MAXIMUM_RESPONSE_DEPTH = 16;
    private static final int MAXIMUM_DOCUMENT_DEPTH = 8;
    private static final int MAXIMUM_NEXT_CHARACTERS = 2_048;
    private static final int MAXIMUM_TITLE_POINTS = 128;
    private static final int MAXIMUM_DETAIL_POINTS = 2_048;
    private static final int MAXIMUM_CODE_CHARACTERS = 64;
    /** A header a Service reads is bounded like any other untrusted input. */
    private static final int MAXIMUM_HEADER_ENTRIES = 64;

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 60;
    private static final int SERVICE_DOCUMENT_SECONDS = 14_400;
    private static final int COLLECTION_SECONDS = 3_600;
    private static final int OFFERING_SECONDS = 300;
    private static final int TAG_CHARACTERS = 22;

    private static final String MEDIA_TYPE = "application/odp+json";
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
    private static final String GET = "GET";
    private static final String HEAD = "HEAD";
    private static final String POST = "POST";
    private static final String TERSE = "terse";
    private static final String FULL = "full";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String NOT_FOUND_DETAIL = "ODP endpoint not found";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String ACCEPT_LANGUAGE = "Accept-Language";
    private static final String VERSION_MEMBER = "odp_version";
    private static final String ANY_APPLICATION = "application/*";
    private static final String ANY_MEDIA = "*/*";
    private static final String ANY = "*";
    private static final String COLLECTIONS = "collections";
    private static final String OFFERINGS = "offerings";
    private static final String SEARCH = "search";
    private static final char QUOTE = '"';
    private static final int MINIMUM_PAGE_ITEMS = 1;
    private static final int ONE_VALUE = 1;
    private static final int MAXIMUM_PAGE_ITEMS = 100;

    private static final Set<String> SAFE_METHODS = Set.of(GET, HEAD);
    private static final Set<String> SEARCH_METHODS = Set.of(GET, HEAD, POST);

    private final ServiceDocument serviceDocument;
    private final Map<OdpOperation, Endpoint> endpoints;
    private final String endpointBase;
    private final List<String> localizations;

    public static Builder builder(String name, String description, String language, String endpointBase) {
        return new Builder(name, description, language, endpointBase);
    }

    public OdpService(ServiceDocument template, Map<OdpOperation, Endpoint> endpoints) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(endpoints, "endpoints");
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
        // SVC-05/SVC-57 make localizations a required, non-empty member, so there is always a list.
        this.localizations = List.copyOf(this.serviceDocument.localizations());
        OdpJson.parseServiceDocument(OdpJson.write(this.serviceDocument));
    }

    public ServiceDocument document() {
        return serviceDocument.toBuilder().build();
    }

    /**
     * Answers one ODP request. Every outcome is an ODP response: a handler that fails in a way this
     * Service did not ask for becomes an {@code INTERNAL_ERROR} problem rather than escaping to the
     * framework, and no exception message a handler did not choose to publish reaches the caller.
     */
    public OdpHttpResponse handle(OdpHttpRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return answer(request);
        } catch (OdpServiceException exception) {
            return problem(
                    exception.status(),
                    exception.code(),
                    exception.getMessage(),
                    exception.retryAfter(),
                    exception.allow() == null ? Map.of() : Map.of("Allow", exception.allow()));
        } catch (RuntimeException exception) {
            // A handler's own failure is not a message to publish: it can name internal state.
            return problem(500, INTERNAL_ERROR, "ODP request could not be processed", null);
        }
    }

    private OdpHttpResponse answer(OdpHttpRequest request) {
        if (byteLength(request.body()) > MAXIMUM_REQUEST_BYTES) {
            throw new OdpServiceException(413, "REQUEST_TOO_LARGE", "ODP request exceeds its byte limit");
        }
        Route route = route(request);
        requireAcceptable(request);
        String language = selectLanguage(request);

        if (route.operation() == null) {
            return served(request, OdpJson.write(serviceDocument), language, SERVICE_DOCUMENT_SECONDS, true, true);
        }
        Endpoint endpoint = endpoints.get(route.operation());
        if (endpoint == null) {
            throw new OdpServiceException(404, NOT_FOUND, NOT_FOUND_DETAIL);
        }
        requireRequestMediaType(request);

        String representation = requireRepresentation(request);
        Integer limit = requireLimit(request);
        String cursor = requireSingle(request, "cursor");
        CatalogRequest catalogRequest = new CatalogRequest(
                route.identifier(), representation, limit, cursor, language, request.body(), request);

        Object response = endpoint.handler().handle(catalogRequest);
        if (response == null) {
            throw new OdpServiceException(404, NOT_FOUND, "ODP resource not found");
        }
        String json = OdpJson.write(response);
        validateResponse(route.operation(), json, representation, cursor);
        boolean cacheable = endpoint.authentication() == AuthenticationRequirement.NOT_REQUIRED;
        return served(request, json, language, freshness(route.operation()), cacheable, false);
    }

    // -- request negotiation ----------------------------------------------------------------

    /**
     * MED-04: an ODP resource is served only when the request will take {@code application/odp+json}.
     * An absent field, a wildcard, and a range that covers the type all qualify; a range that scores
     * it {@code q=0} excludes it as surely as leaving it out.
     */
    private static void requireAcceptable(OdpHttpRequest request) {
        List<String> fields = headerValues(request, "Accept");
        if (fields.isEmpty()) {
            return;
        }
        double best = -1;
        int precision = -1;
        boolean stated = false;
        for (String field : fields) {
            for (String entry : entries(field)) {
                stated = true;
                String[] parts = entry.split(";");
                int scored = score(parts[0].trim().toLowerCase(Locale.ROOT));
                if (scored < 0 || scored < precision) {
                    continue;
                }
                double quality = quality(parts);
                // The most specific range that covers the type decides, as RFC 9110 12.5.1 says.
                if (scored > precision || quality > best) {
                    precision = scored;
                    best = quality;
                }
            }
        }
        if (stated && best <= 0) {
            throw new OdpServiceException(406, "NOT_ACCEPTABLE", "ODP resources are application/odp+json");
        }
    }

    /** How specifically a media range names the ODP media type, or -1 when it does not name it. */
    private static int score(String range) {
        if (MEDIA_TYPE.equals(range)) {
            return 2;
        }
        if (ANY_APPLICATION.equals(range)) {
            return 1;
        }
        return ANY_MEDIA.equals(range) ? 0 : -1;
    }

    private static double quality(String... parts) {
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].trim();
            if (parameter.regionMatches(true, 0, "q=", 0, 2)) {
                try {
                    return Double.parseDouble(parameter.substring(2).trim());
                } catch (NumberFormatException exception) {
                    // An unreadable quality is no quality; nothing about it is worth reporting.
                    return 0;
                }
            }
        }
        return 1;
    }

    /** MED-06: a body carried into an ODP operation is an ODP document or it is not read. */
    private static void requireRequestMediaType(OdpHttpRequest request) {
        if (request.body() == null || request.body().isEmpty()) {
            return;
        }
        String declared = request.headerValue(CONTENT_TYPE);
        if (declared == null || !MEDIA_TYPE.equals(essence(declared))) {
            throw new OdpServiceException(
                    415, "UNSUPPORTED_MEDIA_TYPE", "ODP request bodies must use application/odp+json");
        }
    }

    /** MED-09: media-type essence comparison ignores case and parameters. */
    private static String essence(String value) {
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    /**
     * SVC-58/59: the response language is chosen by the RFC 4647 Lookup scheme against the advertised
     * localizations, and a request whose ranges match nothing gets the default representation rather
     * than a refusal.
     */
    private String selectLanguage(OdpHttpRequest request) {
        List<String> fields = headerValues(request, ACCEPT_LANGUAGE);
        List<Range> ranges = new ArrayList<>();
        for (String field : fields) {
            for (String entry : entries(field)) {
                String[] parts = entry.split(";");
                String range = parts[0].trim().toLowerCase(Locale.ROOT);
                if (!range.isEmpty()) {
                    ranges.add(new Range(range, quality(parts)));
                }
            }
        }
        ranges.sort(Comparator.comparingDouble(Range::quality).reversed());
        for (Range range : ranges) {
            if (range.quality() <= 0) {
                continue;
            }
            String matched = lookup(range.value());
            if (matched != null) {
                return matched;
            }
        }
        return serviceDocument.language();
    }

    /** RFC 4647 Lookup: the range is shortened at each subtag boundary until a tag matches. */
    private String lookup(String range) {
        if (ANY.equals(range)) {
            return serviceDocument.language();
        }
        String candidate = range;
        while (!candidate.isEmpty()) {
            for (String tag : localizations) {
                if (tag.equalsIgnoreCase(candidate)) {
                    return tag;
                }
            }
            int cut = candidate.lastIndexOf('-');
            if (cut < 0) {
                return null;
            }
            candidate = candidate.substring(0, cut);
            // A truncation that leaves a single-character subtag keeps going past it.
            if (candidate.length() > 1 && candidate.charAt(candidate.length() - 2) == '-') {
                candidate = candidate.substring(0, candidate.length() - 2);
            }
        }
        return null;
    }

    /** SVC-73: a repeated value is as unusable as an unsupported one, and is refused the same way. */
    private static String requireRepresentation(OdpHttpRequest request) {
        String representation = requireSingle(request, "representation");
        if (representation == null) {
            return TERSE;
        }
        if (!TERSE.equals(representation) && !FULL.equals(representation)) {
            throw new OdpServiceException(400, INVALID_REQUEST, "representation must be terse or full");
        }
        return representation;
    }

    private static Integer requireLimit(OdpHttpRequest request) {
        String value = requireSingle(request, "limit");
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < MINIMUM_PAGE_ITEMS || parsed > MAXIMUM_PAGE_ITEMS) {
                throw new NumberFormatException(value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new OdpServiceException(
                    400, INVALID_REQUEST, "limit must be from 1 through " + MAXIMUM_PAGE_ITEMS, exception);
        }
    }

    private static String requireSingle(OdpHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > ONE_VALUE) {
            throw new OdpServiceException(400, INVALID_REQUEST, name + " must not be repeated");
        }
        return values.get(0);
    }

    private static List<String> headerValues(OdpHttpRequest request, String name) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                for (String value : entry.getValue()) {
                    if (found.size() >= MAXIMUM_HEADER_ENTRIES) {
                        return found;
                    }
                    found.add(value);
                }
            }
        }
        return found;
    }

    private static List<String> entries(String field) {
        List<String> found = new ArrayList<>();
        for (String entry : field.split(",")) {
            if (!entry.isBlank()) {
                if (found.size() >= MAXIMUM_HEADER_ENTRIES) {
                    return found;
                }
                found.add(entry);
            }
        }
        return found;
    }

    // -- routing ----------------------------------------------------------------------------

    /**
     * Resolves the target of a request. A path this Service publishes but by another method is
     * answered {@code 405} with {@code Allow} rather than pretending the resource is absent, and a
     * path segment standing where an identifier belongs is an identifier or the route does not exist.
     */
    private Route route(OdpHttpRequest request) {
        String path = request.path();
        if (Odp.SERVICE_DOCUMENT_PATH.equals(path)) {
            requireMethod(request, SAFE_METHODS);
            return new Route(null, null);
        }
        if (!path.startsWith(endpointBase + "/")) {
            throw new OdpServiceException(404, NOT_FOUND, NOT_FOUND_DETAIL);
        }
        String[] parts = path.substring(endpointBase.length()).split("/", -1);
        Route route = shape(parts);
        if (route == null) {
            throw new OdpServiceException(404, NOT_FOUND, NOT_FOUND_DETAIL);
        }
        requireMethod(
                request,
                route.operation() == OdpOperation.SEARCH_COLLECTIONS
                                || route.operation() == OdpOperation.SEARCH_OFFERINGS
                        ? SEARCH_METHODS
                        : SAFE_METHODS);
        return route;
    }

    private static Route shape(String... parts) {
        // parts[0] is the empty string before the leading slash; a trailing slash leaves an empty
        // final segment, so "/offerings/plant-1/" never reaches the resource route.
        if (parts.length == 2 && COLLECTIONS.equals(parts[1])) {
            return new Route(OdpOperation.LIST_COLLECTIONS, null);
        }
        if (parts.length == 2 && OFFERINGS.equals(parts[1])) {
            return new Route(OdpOperation.LIST_OFFERINGS, null);
        }
        if (parts.length == 3 && COLLECTIONS.equals(parts[1]) && SEARCH.equals(parts[2])) {
            return new Route(OdpOperation.SEARCH_COLLECTIONS, null);
        }
        if (parts.length == 3 && OFFERINGS.equals(parts[1]) && SEARCH.equals(parts[2])) {
            return new Route(OdpOperation.SEARCH_OFFERINGS, null);
        }
        if (parts.length == 3 && COLLECTIONS.equals(parts[1]) && identifier(parts[2])) {
            return new Route(OdpOperation.GET_COLLECTION, parts[2]);
        }
        if (parts.length == 3 && OFFERINGS.equals(parts[1]) && identifier(parts[2])) {
            return new Route(OdpOperation.GET_OFFERING, parts[2]);
        }
        if (parts.length == 4 && COLLECTIONS.equals(parts[1]) && identifier(parts[2]) && OFFERINGS.equals(parts[3])) {
            return new Route(OdpOperation.LIST_COLLECTION_OFFERINGS, parts[2]);
        }
        return null;
    }

    /** IDN-08: a path segment naming a resource is a Local Resource Identifier or it names nothing. */
    private static boolean identifier(String value) {
        return OdpUris.isLocalResourceIdentifier(value);
    }

    private static void requireMethod(OdpHttpRequest request, Set<String> allowed) {
        if (!allowed.contains(request.method())) {
            String allow = String.join(", ", sorted(allowed));
            throw OdpServiceException.notAllowed("ODP resource accepts " + allow, allow);
        }
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    // -- responses --------------------------------------------------------------------------

    /**
     * Serializes one successful response. A GET or HEAD carries a validator so an Agent can revalidate
     * it, and the conditional fields RFC 9110 defines are honoured before the body is written.
     */
    private OdpHttpResponse served(
            OdpHttpRequest request, String json, String language, int seconds, boolean cacheable, boolean document) {
        requireWithinLimits(
                json,
                document ? MAXIMUM_DOCUMENT_BYTES : MAXIMUM_RESPONSE_BYTES,
                document ? MAXIMUM_DOCUMENT_DEPTH : MAXIMUM_RESPONSE_DEPTH);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CONTENT_TYPE, MEDIA_TYPE);
        headers.put("Content-Language", language);
        headers.put("Vary", ACCEPT_LANGUAGE);
        headers.put(CACHE_CONTROL, cacheHeader(seconds, cacheable));
        String tag = entityTag(json, language);
        headers.put("ETag", tag);

        // RFC 9110 13.2.2: If-Match is evaluated first, and a failed precondition never reaches a body.
        if (!matches(headerValues(request, "If-Match"), tag, true)) {
            throw new OdpServiceException(412, "PRECONDITION_FAILED", "ODP resource has changed");
        }
        if (!matches(headerValues(request, "If-None-Match"), tag, false)) {
            headers.remove(CONTENT_TYPE);
            return new OdpHttpResponse(304, headers, "");
        }
        if (HEAD.equals(request.method())) {
            headers.put("Content-Length", Integer.toString(byteLength(json)));
            return new OdpHttpResponse(200, headers, "");
        }
        return new OdpHttpResponse(200, headers, json);
    }

    private static String cacheHeader(int seconds, boolean cacheable) {
        if (seconds == 0) {
            return "no-store";
        }
        return (cacheable ? "public" : "private") + ", max-age=" + seconds;
    }

    private static int freshness(OdpOperation operation) {
        return switch (operation) {
            case SEARCH_COLLECTIONS, SEARCH_OFFERINGS -> 0;
            case LIST_COLLECTIONS, GET_COLLECTION -> COLLECTION_SECONDS;
            case LIST_COLLECTION_OFFERINGS, LIST_OFFERINGS, GET_OFFERING -> OFFERING_SECONDS;
        };
    }

    /** SVC-61: the validator covers the variant, so two languages never share an entity tag. */
    private static String entityTag(String json, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(language.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(json.getBytes(StandardCharsets.UTF_8));
            return '"'
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(digest.digest())
                            .substring(0, TAG_CHARACTERS)
                    + '"';
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Evaluates one conditional field. An absent field passes; {@code *} matches any current
     * representation; otherwise the tags are compared with the weak comparison RFC 9110 defines,
     * which is the correct one for both fields here because this Service issues only strong tags.
     */
    private static boolean matches(List<String> fields, String tag, boolean expected) {
        if (fields.isEmpty()) {
            return true;
        }
        for (String field : fields) {
            for (String entry : entries(field)) {
                String candidate = entry.trim();
                if (ANY.equals(candidate)) {
                    return expected;
                }
                if (candidate.startsWith("W/")) {
                    candidate = candidate.substring(2);
                }
                if (candidate.equals(tag)) {
                    return expected;
                }
            }
        }
        return !expected;
    }

    // -- response validation ----------------------------------------------------------------

    private static void validateResponse(OdpOperation operation, String json, String representation, String cursor) {
        requireWithinLimits(json, MAXIMUM_RESPONSE_BYTES, MAXIMUM_RESPONSE_DEPTH);
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
        if (isPage(operation)) {
            validatePage(json, cursor);
        }
    }

    private static boolean isPage(OdpOperation operation) {
        return operation != OdpOperation.GET_COLLECTION && operation != OdpOperation.GET_OFFERING;
    }

    /**
     * VER-03/VER-04, PAG-06/07/11: a page's items inherit the version of the document that carries
     * them, and its continuation is a bounded same-origin reference that advances the traversal.
     */
    private static void validatePage(String json, String cursor) {
        OdpJsonNode page = OdpJson.parseTree(json);
        OdpJsonNode items = page.get("items");
        if (items != null && items.isArray()) {
            for (OdpJsonNode item : items) {
                if (item.isObject() && item.get(VERSION_MEMBER) != null) {
                    throw invalidCatalogResponse();
                }
            }
        }
        OdpJsonNode next = page.get("next");
        if (next == null || next.isNull()) {
            return;
        }
        if (!next.isString()) {
            throw invalidCatalogResponse();
        }
        String reference = next.asString();
        if (reference.length() > MAXIMUM_NEXT_CHARACTERS || !reference.startsWith("/") || reference.startsWith("//")) {
            throw invalidCatalogResponse();
        }
        if (cursor != null && cursor.equals(parameter(reference, "cursor"))) {
            // PAG-11: a continuation that hands back the cursor it was given never terminates.
            throw invalidCatalogResponse();
        }
    }

    private static String parameter(String reference, String name) {
        int start = reference.indexOf('?');
        if (start < 0) {
            return null;
        }
        for (String pair : reference.substring(start + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static void validateOffering(Offering offering, String representation) {
        if (TERSE.equals(representation) && offering.actions() != null) {
            throw invalidCatalogResponse();
        }
        if (FULL.equals(representation) && offering.detailFields() != null) {
            throw invalidCatalogResponse();
        }
    }

    private static void validateCollection(Collection collection, String representation) {
        if (FULL.equals(representation) && collection.detailFields() != null) {
            throw invalidCatalogResponse();
        }
    }

    private static OdpServiceException invalidCatalogResponse() {
        return new OdpServiceException(500, INTERNAL_ERROR, "ODP catalog returned an invalid response");
    }

    /** ERR-19: a document this Service would not accept from anybody else is not one it sends. */
    private static void requireWithinLimits(String json, int bytes, int allowedDepth) {
        if (byteLength(json) > bytes || depth(json) > allowedDepth) {
            throw new OdpServiceException(500, INTERNAL_ERROR, "ODP response exceeds its limits");
        }
    }

    /** Nesting depth read off the serialized form, so no second tree is built to measure it. */
    private static int depth(String json) {
        int deepest = 0;
        int current = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (inString && character == '\\') {
                escaped = true;
            } else if (character == QUOTE) {
                inString = !inString;
            } else if (!inString && (character == '{' || character == '[')) {
                current++;
                deepest = Math.max(deepest, current);
            } else if (!inString && (character == '}' || character == ']')) {
                current--;
            }
        }
        return deepest;
    }

    private static int byteLength(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    // -- problems ---------------------------------------------------------------------------

    /**
     * ERR-02/04/06/07: a problem carries a well-formed code, a type derived from it, and strings
     * within their limits. A code a handler invented that ODP could not carry becomes
     * {@code INTERNAL_ERROR}, because an invalid code would make the whole document invalid.
     */
    private static OdpHttpResponse problem(int status, String rawCode, String rawDetail, Integer retryAfter) {
        return problem(status, rawCode, rawDetail, retryAfter, Map.of());
    }

    private static OdpHttpResponse problem(
            int status, String rawCode, String rawDetail, Integer retryAfter, Map<String, String> extra) {
        int reported = status < 400 || status > 599 ? 500 : status;
        String code = isCode(rawCode) ? rawCode : (reported < 500 ? INVALID_REQUEST : INTERNAL_ERROR);
        ProblemDetails details = new ProblemDetails(
                "https://offeringprotocol.org/problems/"
                        + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                title(code),
                reported,
                code,
                bounded(rawDetail, MAXIMUM_DETAIL_POINTS),
                null,
                null,
                Map.of());
        // ERR-21 bounds a Problem Details response at 16,384 bytes, and nothing here can reach it:
        // the type and title are built from a code of at most 64 characters and the detail is cut to
        // 2,048 code points, which is at most 8,192 bytes of UTF-8.
        String json = OdpJson.write(details);
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put(CONTENT_TYPE, PROBLEM_MEDIA_TYPE);
        headers.put(CACHE_CONTROL, "no-store");
        // ERR-32 makes Retry-After part of a 429; ERR-33 asks for it on a 503.
        if (reported == 429 || reported == 503) {
            headers.put("Retry-After", Integer.toString(retryAfter == null ? DEFAULT_RETRY_AFTER_SECONDS : retryAfter));
        } else if (retryAfter != null) {
            headers.put("Retry-After", Integer.toString(retryAfter));
        }
        return new OdpHttpResponse(reported, headers, json);
    }

    /** ERR-06: 1-64 uppercase ASCII letters, digits, or underscores, beginning with a letter. */
    private static boolean isCode(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_CODE_CHARACTERS) {
            return false;
        }
        if (value.charAt(0) < 'A' || value.charAt(0) > 'Z') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** A short human-readable summary of the code, which is what ERR-02 asks {@code title} to be. */
    private static String title(String code) {
        String words = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return bounded(Character.toUpperCase(words.charAt(0)) + words.substring(1), MAXIMUM_TITLE_POINTS);
    }

    private static String bounded(String value, int points) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= points) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, points - 1)) + "…";
    }

    public record Endpoint(AuthenticationRequirement authentication, CatalogHandler handler) {
        public Endpoint {
            Objects.requireNonNull(authentication);
            Objects.requireNonNull(handler);
        }
    }

    private record Range(String value, double quality) {}

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
            Map<OdpOperation, Endpoint> resolved = new EnumMap<>(OdpOperation.class);
            resolved.putAll(this.configuredEndpoints);
            configuredOperationAuthentication.forEach((operation, requirement) -> {
                Endpoint endpoint = resolved.get(operation);
                if (endpoint == null) {
                    throw new IllegalArgumentException(
                            "authentication requirement refers to an unconfigured operation: " + operation.value());
                }
                resolved.put(operation, new Endpoint(requirement, endpoint.handler()));
            });
            return new OdpService(template, resolved);
        }
    }

    /** A resolved target. A null operation is the Service Document itself. */
    private record Route(OdpOperation operation, String identifier) {}
}
