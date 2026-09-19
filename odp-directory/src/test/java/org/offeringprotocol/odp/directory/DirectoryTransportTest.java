package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class DirectoryTransportTest {
    @Test
    void usesMixedAndServiceOnlyRoutesWithOpaqueContinuation() {
        StubClient transport = new StubClient();
        DirectoryClient directory = DirectoryClient.create(DirectoryEnvironment.SANDBOX, transport);
        transport.add(200, DirectoryResultsTest.response(DirectoryResultsTest.result("collection")), Map.of());
        assertEquals(
                1,
                directory
                        .search(new DirectoryModels.ResourceSearchRequest("we", null, 10, null))
                        .items()
                        .size());
        transport.add(200, "{\"items\":[],\"next\":\"/v1/directory/search?cursor=opaque\"}", Map.of());
        assertEquals(
                "/v1/directory/search?cursor=opaque",
                directory.continueSearch("/v1/directory/search?cursor=opaque").next());
        transport.add(200, "{\"items\":[" + DirectoryResultsTest.SERVICE + "]}", Map.of());
        assertEquals(
                1,
                directory
                        .searchServices(new DirectoryModels.SearchRequest("we", null, 10))
                        .items()
                        .size());
        transport.add(200, "{\"items\":[\"Weather forecasts\"]}", Map.of());
        assertEquals(List.of("Weather forecasts"), directory.suggest("we", 10));
        transport.add(200, "{\"items\":[\"weather\"]}", Map.of());
        assertEquals(List.of("weather"), directory.suggestServices("we", 10));
        assertEquals(
                List.of("POST", "GET", "POST", "GET", "GET"),
                transport.requests.stream().map(HttpRequest::method).toList());
        assertEquals(
                List.of(
                        "/v1/directory/search",
                        "/v1/directory/search",
                        "/v1/services/search",
                        "/v1/directory/suggestions",
                        "/v1/services/suggestions"),
                transport.requests.stream()
                        .map(request -> request.uri().getPath())
                        .toList());
        assertTrue(transport.requests.stream()
                .allMatch(request -> "sandbox.inflowpay.ai".equals(request.uri().getHost())));
        assertEquals("cursor=opaque", transport.requests.get(1).uri().getRawQuery());
        assertEquals("prefix=we&limit=10", transport.requests.get(3).uri().getRawQuery());
        assertEquals("{\"query\":\"we\",\"limit\":10}", transport.bodies.get(0));
        assertEquals("", transport.bodies.get(1));
    }

    @Test
    void retainsTransportBoundariesAndRequestErrors() {
        StubClient transport = new StubClient();
        DirectoryClient directory = DirectoryClient.create(DirectoryEnvironment.PRODUCTION, transport);
        var request = new DirectoryModels.ResourceSearchRequest(null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> directory.continueSearch("https://other.example/"));
        assertThrows(IllegalArgumentException.class, () -> directory.continueSearch(" "));
        assertThrows(IllegalArgumentException.class, () -> directory.suggest("", 10));
        assertThrows(IllegalArgumentException.class, () -> directory.suggest("we", 26));
        assertTrue(transport.requests.isEmpty());
        transport.add(307, "", Map.of("Location", List.of("https://other.example/")));
        assertThrows(IllegalArgumentException.class, () -> directory.search(request));
        assertEquals(1, transport.requests.size());
        transport.add(303, "", Map.of("Location", List.of("/redirected")));
        transport.add(200, "{\"items\":[]}", Map.of());
        assertTrue(directory.search(request).items().isEmpty());
        assertEquals("GET", transport.requests.get(2).method());
        assertEquals("", transport.bodies.get(2));
        transport.add(429, "rate limited", Map.of("Retry-After", List.of("5")));
        var error = assertThrows(DirectoryRequestException.class, () -> directory.search(request));
        assertEquals(429, error.status());
        transport.add(200, "x".repeat(524_289), Map.of());
        assertThrows(IllegalStateException.class, () -> directory.search(request));
        transport.add(200, "{\"items\":[]}", Map.of("Content-Type", List.of("text/html")));
        assertThrows(IllegalStateException.class, () -> directory.search(request));
    }

    private record Reply(int status, String body, Map<String, List<String>> headers) {}

    private static final class StubClient extends HttpClient {
        private final HttpClient defaults = HttpClient.newHttpClient();
        private final Deque<Reply> replies = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();

        void add(int status, String body, Map<String, List<String>> headers) {
            replies.add(new Reply(status, body, headers));
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            requests.add(request);
            var requestBody = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            request.bodyPublisher()
                    .orElseGet(HttpRequest.BodyPublishers::noBody)
                    .subscribe(new Flow.Subscriber<>() {
                        public void onSubscribe(Flow.Subscription subscription) {
                            requestBody.onSubscribe(subscription);
                        }

                        public void onNext(ByteBuffer value) {
                            requestBody.onNext(List.of(value));
                        }

                        public void onError(Throwable error) {
                            requestBody.onError(error);
                        }

                        public void onComplete() {
                            requestBody.onComplete();
                        }
                    });
            bodies.add(requestBody.getBody().toCompletableFuture().join());
            Reply reply = replies.remove();
            Map<String, List<String>> values = new java.util.LinkedHashMap<>(reply.headers());
            values.putIfAbsent("Content-Type", List.of("application/json"));
            HttpHeaders headers = HttpHeaders.of(values, (name, value) -> true);
            HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
                public int statusCode() {
                    return reply.status();
                }

                public HttpHeaders headers() {
                    return headers;
                }

                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long amount) {}

                public void cancel() {}
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            T body = subscriber.getBody().toCompletableFuture().join();
            return new HttpResponse<>() {
                public int statusCode() {
                    return reply.status();
                }

                public HttpRequest request() {
                    return request;
                }

                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                public HttpHeaders headers() {
                    return headers;
                }

                public T body() {
                    return body;
                }

                public Optional<SSLSession> sslSession() {
                    return Optional.empty();
                }

                public URI uri() {
                    return request.uri();
                }

                public Version version() {
                    return Version.HTTP_1_1;
                }
            };
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return defaults.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return defaults.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return defaults.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return defaults.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return defaults.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return defaults.authenticator();
        }

        @Override
        public Version version() {
            return defaults.version();
        }

        @Override
        public Optional<Executor> executor() {
            return defaults.executor();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }
    }
}
