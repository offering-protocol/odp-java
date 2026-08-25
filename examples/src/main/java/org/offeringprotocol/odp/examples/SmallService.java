package org.offeringprotocol.odp.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import org.offeringprotocol.odp.core.AuthenticationRequirement;
import org.offeringprotocol.odp.core.Collection;
import org.offeringprotocol.odp.core.Odp;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.Offering;
import org.offeringprotocol.odp.core.Page;
import org.offeringprotocol.odp.service.OdpHttpRequest;
import org.offeringprotocol.odp.service.OdpHttpResponse;
import org.offeringprotocol.odp.service.OdpService;
import org.offeringprotocol.odp.service.StaticCatalog;

/** Runnable small-catalog ODP Service. */
public final class SmallService {
    private SmallService() {}

    public static void main(String[] arguments) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4103"));
        List<Offering> offerings = offerings();
        Map<OdpOperation, OdpService.Endpoint> endpoints =
                new EnumMap<>(StaticCatalog.create(offerings, List.of(collection())));
        endpoints.put(
                OdpOperation.SEARCH_OFFERINGS,
                new OdpService.Endpoint(AuthenticationRequirement.NOT_REQUIRED, request -> {
                    String query =
                            OdpJson.parseOfferingSearchRequest(request.body()).query();
                    String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
                    List<Offering> matches = offerings.stream()
                            .filter(offering -> (offering.name() + " " + offering.description())
                                    .toLowerCase(Locale.ROOT)
                                    .contains(normalized))
                            .toList();
                    return new Page<>(null, Odp.VERSION, matches, null, Map.of());
                }));
        OdpService service = OdpService.builder(
                        "ODP Developer Resources", "Free resources for ODP integrators", "en", "/odp")
                .keywords(List.of("agent", "developer", "documentation"))
                .endpoints(endpoints)
                .build();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", port), // NOPMD - The example must remain local-only.
                0);
        server.createContext("/downloads/agent-guide.txt", exchange -> {
            byte[] body = "Build Agents against advertised ODP operations.\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/", exchange -> handle(service, exchange));
        server.setExecutor(Executors.newCachedThreadPool());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.printf( // NOPMD - Console output is the example's user interface.
                "Small ODP Service listening at http://127.0.0.1:%d%n", port);
        System.out.printf( // NOPMD - Console output is the example's user interface.
                "Service Document: http://127.0.0.1:%d/.well-known/odp%n", port);
        System.out.printf( // NOPMD - Console output is the example's user interface.
                "Offerings: http://127.0.0.1:%d/odp/offerings%n", port);
    }

    private static void handle(OdpService service, HttpExchange exchange) throws IOException {
        OdpHttpRequest request = new OdpHttpRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                query(exchange.getRequestURI().getRawQuery()),
                exchange.getRequestHeaders(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        OdpHttpResponse response = service.handle(request);
        response.headers().forEach(exchange.getResponseHeaders()::set);
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        System.out.printf( // NOPMD - Request logging makes the example observable.
                "%s %s -> %d%n", request.method(), exchange.getRequestURI(), response.status());
    }

    private static Map<String, List<String>> query(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static Collection collection() {
        return new Collection(
                null,
                Odp.VERSION,
                "resources",
                "Resources",
                "Guides and reference materials",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());
    }

    private static List<Offering> offerings() {
        Offering.Action action = new Offering.Action(
                AuthenticationRequirement.NOT_REQUIRED,
                "download",
                "download",
                "Download the guide",
                new Offering.HttpTarget("/downloads/agent-guide.txt", "GET", null, List.of("text/plain")),
                null);
        Offering guide = new Offering(
                null,
                Odp.VERSION,
                "agent-guide",
                "ODP Agent Guide",
                "A short guide for building an ODP Agent",
                null,
                null,
                null,
                null,
                List.of("resources"),
                new Offering.PricePreview("free", null, null, null, null, null, Map.of()),
                null,
                null,
                List.of(action),
                null,
                Map.of());
        Offering review = new Offering(
                null,
                Odp.VERSION,
                "architecture-review",
                "Architecture Review",
                "A one-time architecture review",
                null,
                null,
                null,
                null,
                null,
                new Offering.PricePreview("starting_at", "500", "USD", null, null, null, Map.of()),
                null,
                null,
                null,
                null,
                Map.of());
        return List.of(guide, review);
    }
}
