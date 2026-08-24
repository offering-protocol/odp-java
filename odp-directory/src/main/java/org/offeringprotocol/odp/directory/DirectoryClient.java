package org.offeringprotocol.odp.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Client for the canonical ODP directory. */
public final class DirectoryClient {
    private static final int MAXIMUM_BYTES = 524_288;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final JsonMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

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
        return decode(
                send(selectedEnvironment.origin().resolve("/v1/services/search"), "POST", encode(request)),
                DirectoryModels.SearchPage.class);
    }

    public DirectoryModels.SearchPage continueSearchServices(String next) {
        URI uri = resolveContinuation(next);
        return decode(send(uri, "GET", null), DirectoryModels.SearchPage.class);
    }

    public List<String> suggestServices(String prefix, Integer limit) {
        if (prefix == null || prefix.isBlank() || prefix.length() > 128) {
            throw new IllegalArgumentException("prefix must contain from 1 through 128 characters");
        }
        if (limit != null && (limit < 1 || limit > 25)) {
            throw new IllegalArgumentException("limit must be from 1 through 25");
        }
        String query = "?prefix=" + URLEncoder.encode(prefix, StandardCharsets.UTF_8)
                + (limit == null ? "" : "&limit=" + limit);
        String json = send(selectedEnvironment.origin().resolve("/v1/services/suggestions" + query), "GET", null);
        try {
            DirectoryModels.Suggestions suggestions = JSON.readValue(json, DirectoryModels.Suggestions.class);
            return suggestions.items();
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Directory suggestions response is invalid", exception);
        }
    }

    private String send(URI uri, String method, String body) {
        URI current = uri;
        String currentMethod = method;
        String currentBody = body;
        boolean hasBody = body != null;
        for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (!hasBody) {
                builder.method(currentMethod, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
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
        throw new IllegalStateException("Directory request produced no response");
    }

    private String consume(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        if (body.length > MAXIMUM_BYTES) {
            throw new IllegalStateException("Directory response exceeds its byte limit");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            throw new DirectoryRequestException(
                    response.statusCode(),
                    text.isEmpty() ? "Directory request failed with HTTP " + response.statusCode() : text,
                    response.headers());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalStateException("Directory response must use application/json");
        }
        return text;
    }

    private URI resolveContinuation(String next) {
        if (next == null || next.isBlank() || next.length() > 2048) {
            throw new IllegalArgumentException("next must contain from 1 through 2048 characters");
        }
        return requireDirectoryOrigin(selectedEnvironment.origin().resolve(next));
    }

    private URI requireDirectoryOrigin(URI uri) {
        URI origin = selectedEnvironment.origin();
        if (!uri.getScheme().equalsIgnoreCase(origin.getScheme())
                || !uri.getAuthority().equalsIgnoreCase(origin.getAuthority())
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Directory continuation must remain on the canonical origin");
        }
        return uri;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Directory request is not encodable", exception);
        }
    }

    private static <T> T decode(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Directory response is invalid", exception);
        }
    }
}
