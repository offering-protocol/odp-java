package org.offeringprotocol.odp.directory;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** A directory that answers exactly what a test tells it to, and records what it was asked. */
final class Stub extends HttpClient {
    private final Exchange exchange;
    private final List<HttpRequest> requests = new ArrayList<>();

    private Stub(Exchange exchange) {
        this.exchange = exchange;
    }

    /** Answers every request the same way. */
    static Stub always(int status, String body, String contentType) {
        return new Stub(request -> reply(request, status, body, headers(contentType, -1)));
    }

    /** Answers a JSON document, the way a healthy directory does. */
    static Stub json(String body) {
        return always(200, body, "application/json");
    }

    /** Answers with a declared length that need not match what arrives. */
    static Stub declaring(String body, String contentType, long declared) {
        return new Stub(request -> reply(request, 200, body, headers(contentType, declared)));
    }

    /** Answers each request from the script in turn, and repeats the last answer after it runs out. */
    static Stub script(Exchange... steps) {
        int[] cursor = {0};
        return new Stub(request -> {
            Exchange step = steps[Math.min(cursor[0], steps.length - 1)];
            cursor[0] += 1;
            return step.reply(request);
        });
    }

    static Exchange redirect(int status, String location) {
        return request -> reply(request, status, "", Map.of("Location", List.of(location)));
    }

    static Exchange reply(int status, String body, String contentType) {
        return request -> reply(request, status, body, headers(contentType, -1));
    }

    static Exchange raw(int status, String body, Map<String, List<String>> headers) {
        return request -> reply(request, status, body, headers);
    }

    List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    HttpRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    DirectoryClient into(DirectoryEnvironment environment) {
        return DirectoryClient.create(environment, this);
    }

    DirectoryClient client() {
        return into(DirectoryEnvironment.SANDBOX);
    }

    private static Map<String, List<String>> headers(String contentType, long declared) {
        if (contentType == null) {
            return Map.of();
        }
        return declared < 0
                ? Map.of("Content-Type", List.of(contentType))
                : Map.of("Content-Type", List.of(contentType), "Content-Length", List.of(Long.toString(declared)));
    }

    private static HttpResponse<byte[]> reply(
            HttpRequest request, int status, String body, Map<String, List<String>> headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new HttpResponse<byte[]>() {
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
                return HttpHeaders.of(headers, (name, value) -> true);
            }

            @Override
            public byte[] body() {
                return bytes.clone();
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
            public Version version() {
                return Version.HTTP_1_1;
            }
        };
    }

    @FunctionalInterface
    interface Exchange {
        HttpResponse<byte[]> reply(HttpRequest request) throws IOException, InterruptedException;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        requests.add(request);
        return (HttpResponse<T>) exchange.reply(request);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException("the directory client sends synchronously");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
