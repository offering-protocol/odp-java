package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.directory.DirectoryClient;
import org.offeringprotocol.odp.directory.DirectoryEnvironment;

class OdpAgentTest {
    private static final String DIRECTORY_RESULTS = """
            {"items":[
            {"service_origin":"https://fast.example","name":"Fast","description":"Fast catalog",
            "language":"en","localizations":["en"],"indexed_at":"2026-08-02T00:00:00Z",
            "operations":[{"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-offerings"}]},
            {"service_origin":"https://slow.example","name":"Slow","description":"Slow catalog",
            "language":"en","localizations":["en"],"indexed_at":"2026-08-02T00:00:00Z",
            "operations":[{"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-offerings"}]}]}
            """;

    private static final String SEARCHING_DOCUMENT = """
            {"odp_version":"1.0","name":"Catalog","description":"A catalog.",
            "language":"en","localizations":["en"],"operations":[
            {"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-offerings"},
            {"authentication":"not-required","name":"search-offerings"}],
            "http":{"endpoint_base":"/odp"}}
            """;

    private static final String LISTING_DOCUMENT = """
            {"odp_version":"1.0","name":"Catalog","description":"A catalog.",
            "language":"en","localizations":["en"],"operations":[
            {"authentication":"not-required","name":"get-offering"},
            {"authentication":"not-required","name":"list-offerings"}],
            "http":{"endpoint_base":"/odp"}}
            """;

    @Test
    void reportsAnOfferingFromEveryServiceThatCanSearch() {
        OdpAgent agent = new OdpAgent(directory(), origin -> service(origin, SEARCHING_DOCUMENT));
        List<OdpAgent.DiscoveryEvent> events = agent.searchOfferings("gpu", 10, 5);
        assertEquals(2, events.size());
        OdpAgent.OfferingEvent first = assertInstanceOf(OdpAgent.OfferingEvent.class, events.get(0));
        assertEquals("https://fast.example", first.service().serviceOrigin());
        assertEquals("GPU", first.offering().name());
    }

    /** A Service that does not advertise search is passed over rather than reported as a failure. */
    @Test
    void skipsAServiceThatCannotSearch() {
        OdpAgent agent = new OdpAgent(directory(), origin -> service(origin, LISTING_DOCUMENT));
        assertTrue(agent.searchOfferings("gpu", 10, 5).isEmpty());
    }

    /** One Service failing is an issue against that Service, not the end of the search. */
    @Test
    void reportsAFailingServiceAsAnIssue() {
        OdpAgent agent = new OdpAgent(directory(), origin -> {
            if (origin.contains("slow")) {
                throw new IllegalStateException("Service stopped answering");
            }
            return service(origin, SEARCHING_DOCUMENT);
        });
        List<OdpAgent.DiscoveryEvent> events = agent.searchOfferings("gpu", 10, 5);
        assertEquals(2, events.size());
        OdpAgent.IssueEvent issue = assertInstanceOf(OdpAgent.IssueEvent.class, events.get(1));
        assertEquals("https://slow.example", issue.service().serviceOrigin());
        assertEquals("Service stopped answering", issue.message());
    }

    @Test
    void refusesBoundsOutsideWhatItWillTraverse() {
        OdpAgent agent = new OdpAgent(directory(), origin -> service(origin, SEARCHING_DOCUMENT));
        assertEquals(
                "maximumServices must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> agent.searchOfferings("gpu", 0, 5))
                        .getMessage());
        assertEquals(
                "maximumServices must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> agent.searchOfferings("gpu", 101, 5))
                        .getMessage());
        assertEquals(
                "offeringsPerService must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> agent.searchOfferings("gpu", 10, 0))
                        .getMessage());
        assertEquals(
                "offeringsPerService must be from 1 through 100",
                assertThrows(IllegalArgumentException.class, () -> agent.searchOfferings("gpu", 10, 101))
                        .getMessage());
    }

    @Test
    void requiresADirectoryAndAFactory() {
        assertThrows(NullPointerException.class, () -> new OdpAgent(null));
        assertThrows(NullPointerException.class, () -> new OdpAgent(directory(), null));
    }

    private static DirectoryClient directory() {
        return DirectoryClient.create(DirectoryEnvironment.SANDBOX, new StubHttpClient());
    }

    private static OdpServiceClient service(String origin, String document) {
        return OdpServiceClient.create(
                URI.create(origin),
                request -> Responses.ok(
                        request,
                        request.uri().getPath().equals("/.well-known/odp")
                                ? document
                                : "{\"odp_version\":\"1.0\",\"items\":[{\"id\":\"gpu\",\"name\":\"GPU\"}]}"));
    }

    /** The directory client speaks to an {@link HttpClient}, so its answers are stubbed at that seam. */
    private static final class StubHttpClient extends HttpClient {
        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<java.time.Duration> connectTimeout() {
            return java.util.Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() {
            return java.util.Optional.empty();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            return null;
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() {
            return java.util.Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() {
            return java.util.Optional.empty();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>)
                    Responses.of(request, 200, DIRECTORY_RESULTS, Map.of("Content-Type", List.of("application/json")));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return java.util.concurrent.CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }
}
