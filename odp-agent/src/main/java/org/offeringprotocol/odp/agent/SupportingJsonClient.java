package org.offeringprotocol.odp.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.offeringprotocol.odp.core.OdpJson;
import org.offeringprotocol.odp.core.OdpJsonNode;

final class SupportingJsonClient {
    private static final int MAXIMUM_REDIRECTS = 5;
    private final OdpTransport transport;

    SupportingJsonClient(OdpTransport transport) {
        this.transport = transport;
    }

    OdpJsonNode get(URI target, String accept, Set<String> mediaTypes, int maximumBytes, int maximumDepth) {
        requireHttps(target);
        URI current = target;
        for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = send(request);
            int status = response.statusCode();
            if (isRedirect(status)) {
                if (redirects == MAXIMUM_REDIRECTS) {
                    throw new IllegalStateException("ODP supporting resource exceeded its redirect limit");
                }
                URI next = current.resolve(response.headers()
                        .firstValue("Location")
                        .orElseThrow(
                                () -> new IllegalStateException("ODP supporting resource redirect omitted Location")));
                requireHttps(next);
                if (!sameOrigin(current, next)) {
                    throw new IllegalStateException("ODP supporting resource redirect changed origin");
                }
                current = next;
            } else {
                if (status < 200 || status > 299) {
                    throw new IllegalStateException("ODP supporting resource request failed with HTTP " + status);
                }
                byte[] bytes = response.body();
                if (bytes.length > maximumBytes) {
                    throw new IllegalStateException("ODP supporting resource exceeds its byte limit");
                }
                String contentType =
                        response.headers().firstValue("Content-Type").orElse("");
                String essence = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!mediaTypes.contains(essence)) {
                    throw new IllegalStateException("ODP supporting resource returned an unsupported Content-Type");
                }
                OdpJsonNode document = parse(bytes);
                if (!document.isObject()) {
                    throw new IllegalStateException("ODP supporting resource must be a JSON object");
                }
                if (depth(document) > maximumDepth) {
                    throw new IllegalStateException("ODP supporting resource exceeds its JSON depth limit");
                }
                return document;
            }
        }
        throw new IllegalStateException("ODP supporting resource produced no response");
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return transport.send(request);
        } catch (IOException exception) {
            throw new IllegalStateException("ODP supporting resource request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ODP supporting resource request was interrupted", exception);
        }
    }

    private static OdpJsonNode parse(byte[] bytes) {
        try {
            return OdpJson.parseTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("ODP supporting resource must contain valid JSON", exception);
        }
    }

    private static int depth(OdpJsonNode value) {
        int maximum = 1;
        for (OdpJsonNode child : value) {
            maximum = Math.max(maximum, 1 + depth(child));
        }
        return maximum;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static void requireHttps(URI target) {
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null) {
            throw new IllegalArgumentException("ODP supporting document URL must use HTTPS");
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI value) {
        return value.getPort() == -1 ? 443 : value.getPort();
    }
}
