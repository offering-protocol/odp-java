package org.offeringprotocol.odp.directory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;
import org.offeringprotocol.odp.core.ServiceDocument;

/** Client for the canonical ODP directory. */
public final class DirectoryClient {
    private static final int MAXIMUM_BYTES = 524_288;
    /** A failure message travels into logs, so an error body is read and quoted far more tightly. */
    private static final int MAXIMUM_ERROR_BYTES = 16_384;

    private static final int MAXIMUM_ERROR_CHARACTERS = 2_048;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final int MAXIMUM_SUGGESTIONS = 25;
    private static final int MAXIMUM_SUGGESTION_CHARACTERS = 128;
    private static final int MAXIMUM_CONTINUATION_CHARACTERS = 2_048;
    private static final String JSON = "application/json";
    private static final String BAD_PAGE = "Directory response is invalid";
    private static final String BAD_SUGGESTIONS = "Directory suggestions response is invalid";

    /**
     * Members a Service Document carries but a directory cannot vouch for. A directory summarizes a
     * Service; it does not serve the Service's own document, so these are dropped rather than
     * passed on as though the Agent had retrieved them.
     */
    private static final List<String> UNVERIFIED =
            List.of("branding", "http", "mcp", "odp_version", "payment_origins", "search_capabilities");

    private final DirectoryEnvironment selectedEnvironment;
    private final HttpClient httpClient;

    private DirectoryClient(DirectoryEnvironment environment, HttpClient httpClient) {
        this.selectedEnvironment = environment;
        this.httpClient = httpClient;
    }

    public static DirectoryClient create() {
        return create(DirectoryEnvironment.PRODUCTION);
    }

    public static DirectoryClient create(DirectoryEnvironment environment) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new DirectoryClient(Objects.requireNonNull(environment), client);
    }

    public static DirectoryClient create(DirectoryEnvironment environment, HttpClient httpClient) {
        return new DirectoryClient(Objects.requireNonNull(environment), Objects.requireNonNull(httpClient));
    }

    public DirectoryEnvironment environment() {
        return selectedEnvironment;
    }

    public DirectoryModels.SearchPage searchServices(DirectoryModels.SearchRequest request) {
        Objects.requireNonNull(request, "request");
        return decodeSearchPage(
                send(selectedEnvironment.origin().resolve("/v1/services/search"), "POST", OdpJson.write(request)));
    }

    public DirectoryModels.SearchPage continueSearchServices(String next) {
        URI uri = resolveContinuation(next);
        return decodeSearchPage(send(uri, "GET", null));
    }

    public List<String> suggestServices(String prefix, Integer limit) {
        if (prefix == null || prefix.isBlank() || prefix.length() > MAXIMUM_SUGGESTION_CHARACTERS) {
            throw new IllegalArgumentException("prefix must contain from 1 through 128 characters");
        }
        if (limit != null && (limit < 1 || limit > MAXIMUM_SUGGESTIONS)) {
            throw new IllegalArgumentException("limit must be from 1 through 25");
        }
        String query = "?prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8)
                + (limit == null ? "" : "&limit=" + limit);
        String json = send(selectedEnvironment.origin().resolve("/v1/services/suggestions" + query), "GET", null);
        return decodeSuggestions(json, limit == null ? MAXIMUM_SUGGESTIONS : limit);
    }

    private String send(URI uri, String method, String body) {
        URI current = uri;
        String currentMethod = method;
        String currentBody = body;
        boolean hasBody = body != null;
        for (int redirects = 0; ; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", JSON);
            if (!hasBody) {
                builder.method(currentMethod, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", JSON)
                        .method(currentMethod, HttpRequest.BodyPublishers.ofString(currentBody));
            }
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException exception) {
                throw new IllegalStateException("Directory request failed", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Directory request was interrupted", exception);
            }
            int status = response.statusCode();
            if (!isRedirect(status)) {
                return consume(response);
            }
            if (redirects == MAXIMUM_REDIRECTS) {
                throw new IllegalStateException("Directory response exceeded its redirect limit");
            }
            String location = response.headers()
                    .firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Directory redirect omitted Location"));
            current = requireDirectoryOrigin(current.resolve(location));
            if (status == 303 || ((status == 301 || status == 302) && "POST".equals(currentMethod))) {
                currentMethod = "GET";
                hasBody = false;
            }
        }
    }

    private String consume(HttpResponse<byte[]> response) {
        boolean failure = response.statusCode() < 200 || response.statusCode() > 299;
        int limit = failure ? MAXIMUM_ERROR_BYTES : MAXIMUM_BYTES;
        // A declared length past the limit is refused before the body is read, and what did arrive
        // is measured before it is decoded.
        response.headers().firstValueAsLong("Content-Length").ifPresent(declared -> {
            if (declared > limit) {
                throw new IllegalStateException("Directory response exceeds its byte limit");
            }
        });
        byte[] body = response.body();
        if (body.length > limit) {
            throw new IllegalStateException("Directory response exceeds its byte limit");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (failure) {
            throw new DirectoryRequestException(
                    response.statusCode(),
                    failureMessage(response.headers(), text, response.statusCode()),
                    response.headers());
        }
        String essence = response.headers()
                .firstValue("Content-Type")
                .orElse("")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!JSON.equals(essence)) {
            throw new IllegalStateException("Directory response must use application/json");
        }
        return text;
    }

    /**
     * Describes a failed request without repeating whatever the response happened to contain. Only a
     * structured field of a JSON error document is quoted, and only after the control characters
     * that would let it forge a log line are removed.
     */
    private static String failureMessage(HttpHeaders headers, String body, int status) {
        String summary = "Directory request failed with HTTP " + status;
        String essence = headers.firstValue("Content-Type")
                .orElse("")
                .split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!JSON.equals(essence) && !"application/problem+json".equals(essence)) {
            return summary;
        }
        OdpJsonNode document;
        try {
            document = OdpJson.parseTree(body);
        } catch (IllegalArgumentException exception) {
            return summary;
        }
        if (document == null || !document.isObject()) {
            return summary;
        }
        String detail = firstDetail(document);
        if (detail.isEmpty()) {
            return summary;
        }
        return summary + ": "
                + (detail.length() > MAXIMUM_ERROR_CHARACTERS
                        ? detail.substring(0, MAXIMUM_ERROR_CHARACTERS) + "…"
                        : detail);
    }

    /** The first structured field of an error document that carries readable text, if any does. */
    private static String firstDetail(OdpJsonNode document) {
        String found = "";
        for (String field : List.of("detail", "title", "message")) {
            OdpJsonNode value = document.get(field);
            if (value != null && value.isString() && found.isEmpty()) {
                found = printableText(value.asString());
            }
        }
        return found;
    }

    private static String printableText(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(point -> cleaned.appendCodePoint(Character.isISOControl(point) ? ' ' : point));
        return String.join(" ", cleaned.toString().trim().split("\\s+"));
    }

    private URI resolveContinuation(String next) {
        if (next == null || next.isBlank() || next.length() > MAXIMUM_CONTINUATION_CHARACTERS) {
            throw new IllegalArgumentException("next must contain from 1 through 2048 characters");
        }
        for (int index = 0; index < next.length(); index++) {
            char character = next.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException("next must contain printable ASCII only");
            }
        }
        return requireDirectoryOrigin(selectedEnvironment.origin().resolve(next));
    }

    private URI requireDirectoryOrigin(URI uri) {
        if (!DirectoryOrigins.sameOrigin(uri, selectedEnvironment.origin())) {
            throw new IllegalArgumentException("Directory continuation must remain on the canonical origin");
        }
        return uri;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static List<String> decodeSuggestions(String json, int limit) {
        OdpJsonNode value;
        try {
            value = OdpJson.parseTree(json);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(BAD_SUGGESTIONS, exception);
        }
        OdpJsonNode items = value == null || !value.isObject() ? null : value.get("items");
        if (items == null || !items.isArray()) {
            throw new IllegalArgumentException(BAD_SUGGESTIONS);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (OdpJsonNode item : items) {
            if (!item.isString()) {
                throw new IllegalArgumentException(BAD_SUGGESTIONS);
            }
            String suggestion = item.asString();
            if (suggestion.isBlank() || suggestion.length() > MAXIMUM_SUGGESTION_CHARACTERS) {
                throw new IllegalArgumentException(BAD_SUGGESTIONS);
            }
            unique.add(suggestion);
        }
        // A directory that answers with more than was asked for is answering a different request;
        // the caller gets what it asked for.
        return unique.stream().limit(limit).toList();
    }

    static DirectoryModels.SearchPage decodeSearchPage(String json) {
        OdpJsonNode value;
        try {
            value = OdpJson.parseTree(json);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(BAD_PAGE, exception);
        }
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(BAD_PAGE);
        }
        // The continuation is what drives the next request, so it is a string or it is not there.
        OdpJsonNode next = value.get("next");
        if (next != null && !next.isNull() && !next.isString()) {
            throw new IllegalArgumentException(BAD_PAGE);
        }
        List<DirectoryModels.Issue> issues = validateResults(value.get("items"));
        // A directory that sends its own issues member is not describing what this client found.
        value.remove("issues");
        DirectoryModels.SearchPage decoded;
        try {
            decoded = OdpJson.treeToValue(value, DirectoryModels.SearchPage.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(BAD_PAGE, exception);
        }
        requireTrustFacets(decoded.facets());
        return new DirectoryModels.SearchPage(
                decoded.items(), decoded.next(), decoded.facets(), issues, decoded.additional());
    }

    /**
     * SVC-43: `tap` is the only trust protocol this ODP version defines, so a facet counting any
     * other name describes a vocabulary this client cannot read, and the page is not usable.
     */
    private static void requireTrustFacets(DirectoryModels.Facets facets) {
        if (facets == null) {
            return;
        }
        for (DirectoryModels.Facet<ServiceDocument.TrustProtocol> facet : facets.trust()) {
            if (facet.value() == null || !"tap".equals(facet.value().name())) {
                throw new IllegalArgumentException("Directory trust facets are invalid");
            }
        }
    }

    /**
     * Validates each result in place, dropping the ones that cannot be used. One unusable record does
     * not discard the page it arrived on; it is reported alongside the results that did survive.
     */
    private static List<DirectoryModels.Issue> validateResults(OdpJsonNode items) {
        List<DirectoryModels.Issue> issues = new ArrayList<>();
        if (items == null || !items.isArray()) {
            return issues;
        }
        List<Boolean> unusable = new ArrayList<>(items.size());
        int index = 0;
        for (OdpJsonNode item : items) {
            try {
                requireResult(item);
                unusable.add(false);
            } catch (RuntimeException exception) {
                issues.add(new DirectoryModels.Issue(index, String.valueOf(exception.getMessage())));
                unusable.add(true);
            }
            index++;
        }
        // removeIf visits the array from its end, so the decision for each result is read back the
        // same way it was recorded.
        int[] cursor = {unusable.size()};
        items.removeIf(item -> {
            cursor[0] -= 1;
            return unusable.get(cursor[0]);
        });
        return issues;
    }

    private static void requireResult(OdpJsonNode service) {
        if (!service.isObject()) {
            throw new IllegalArgumentException("Directory result must be a JSON object");
        }
        OdpJsonNode origin = service.get("service_origin");
        if (origin == null || !origin.isString()) {
            throw new IllegalArgumentException("Directory result service_origin is missing");
        }
        DirectoryOrigins.requireServiceOrigin(origin.asString());
        // The result claims to summarize a Service, so it is held to the shape of the document it
        // summarizes, with the members only the Service itself can supply filled in here.
        OdpJsonNode document = service.deepCopy();
        document.remove(UNVERIFIED);
        document.remove(List.of("service_origin", "indexed_at"));
        document.put("odp_version", "1.0");
        document.putObject("http").put("endpoint_base", "/");
        ServiceDocument parsed = OdpJson.parseAgentServiceDocument(document.toString());
        service.remove(UNVERIFIED);
        if (parsed.protocols() == null) {
            service.remove("protocols");
        } else {
            service.set("protocols", OdpJson.valueToTree(parsed.protocols()));
        }
    }
}
