package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.offeringprotocol.odp.core.OdpOperation;
import org.offeringprotocol.odp.core.ServiceDocument;

class OdpServiceClientTest {
    @Test
    void inspectsAndNavigatesAService() {
        OdpTransport transport = request -> response(
                request,
                switch (request.uri().getPath()) {
                    case "/.well-known/odp" -> """
                    {"odp_version":"1.0","name":"Plant Store","description":"Plants for agents.",
                    "language":"en","localizations":["en"],"operations":[
                    {"authentication":"not-required","name":"get-offering"},
                    {"authentication":"not-required","name":"list-offerings"}],
                    "http":{"endpoint_base":"/odp"}}
                    """;
                    case "/odp/offerings" -> """
                    {"odp_version":"1.0","items":[
                    {"id":"rubber-plant","name":"Rubber Plant","description":"A resilient houseplant."}]}
                    """;
                    default -> throw new AssertionError("Unexpected request " + request.uri());
                });

        OdpServiceClient client = OdpServiceClient.create(URI.create("https://plants.example"), transport);

        assertTrue(client.inspection().supports(OdpOperation.LIST_OFFERINGS));
        assertEquals(
                "Rubber Plant",
                client.listOfferings("terse", 10, "en").items().get(0).name());
    }

    @Test
    void filtersUnknownProtocolsBeforeExposingServiceInspection() {
        OdpTransport transport = request -> response(request, """
                {"odp_version":"1.0","name":"Plant Store","description":"Plants for agents.",
                "language":"en","localizations":["en"],"operations":[
                {"authentication":"not-required","name":"get-offering"},
                {"authentication":"not-required","name":"list-offerings"}],
                "http":{"endpoint_base":"/odp"},"protocols":{
                "enrollment":[{"name":"future-enrollment"},{"name":"aep"}],
                "payments":[{"authentication":"not-required","name":"future-payment"},
                {"authentication":"not-required","name":"mpp"}],
                "trust":[{"name":"future-trust"},{"name":"tap"}]}}
                """);

        ServiceDocument.Protocols protocols = OdpServiceClient.create(URI.create("https://plants.example"), transport)
                .inspection()
                .document()
                .protocols();

        assertEquals(List.of(new ServiceDocument.EnrollmentProtocol("aep")), protocols.enrollment());
        assertEquals(List.of(new ServiceDocument.TrustProtocol("tap")), protocols.trust());
        assertEquals(1, protocols.payments().size());
    }

    private static HttpResponse<byte[]> response(HttpRequest request, String body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 200;
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
                return HttpHeaders.of(Map.of("Content-Type", List.of("application/odp+json")), (a, b) -> true);
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
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
}
