package org.offeringprotocol.odp.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/** Builds the responses a transport hands back, so a test states exactly what a Service said. */
final class Responses {
    static final String ODP = "application/odp+json";
    static final String PROBLEM = "application/problem+json";

    static final String SERVICE_DOCUMENT = """
            {"odp_version":"1.0","name":"Plant Store","description":"Plants for agents.",
            "language":"en","localizations":["en"],"operations":[
            {"authentication":"not-required","name":"get-collection"},
            {"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-collection-offerings"},
            {"authentication":"not-required","name":"list-collections"},
            {"authentication":"not-required","name":"list-offerings"},
            {"authentication":"not-required","name":"search-collections"},
            {"authentication":"not-required","name":"search-offerings"}],
            "http":{"endpoint_base":"/odp"}}
            """;

    private Responses() {}

    static HttpResponse<byte[]> ok(HttpRequest request, String body) {
        return of(request, 200, body, Map.of("Content-Type", List.of(ODP)));
    }

    static HttpResponse<byte[]> of(HttpRequest request, int status, String body, Map<String, List<String>> headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> values = new LinkedHashMap<>(headers);
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
                return HttpHeaders.of(values, (left, right) -> true);
            }

            @Override
            public byte[] body() {
                return bytes;
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

    /** A client whose Service Document is the shared one and whose catalog answers from {@code catalog}. */
    static OdpServiceClient serving(java.util.function.Function<HttpRequest, String> catalog) {
        return OdpServiceClient.create(
                URI.create("https://plants.example"),
                request -> request.uri().getPath().equals("/.well-known/odp")
                        ? ok(request, SERVICE_DOCUMENT)
                        : ok(request, catalog.apply(request)));
    }

    /** Records every request a client made, so a test can assert on what went out. */
    static final class Recorder {
        private final List<HttpRequest> requests = new ArrayList<>();

        List<HttpRequest> requests() {
            return List.copyOf(requests);
        }

        HttpRequest last() {
            return requests.get(requests.size() - 1);
        }

        void record(HttpRequest request) {
            requests.add(request);
        }
    }
}
