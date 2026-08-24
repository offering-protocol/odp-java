package org.offeringprotocol.odp.core;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** ODP origin, operation, and reference resolution. */
public final class OdpUris {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private OdpUris() {}

    public static String deriveServiceOrigin(URI serviceDocumentUri) {
        requireSecure(serviceDocumentUri);
        if (serviceDocumentUri.getUserInfo() != null) {
            throw new IllegalArgumentException("Service URI cannot contain user information");
        }
        String host = IDN.toASCII(serviceDocumentUri.getHost()).toLowerCase(Locale.ROOT);
        int port = serviceDocumentUri.getPort();
        boolean defaultPort = ("https".equals(serviceDocumentUri.getScheme()) && port == 443)
                || ("http".equals(serviceDocumentUri.getScheme()) && port == 80);
        return serviceDocumentUri.getScheme() + "://" + host + (port < 0 || defaultPort ? "" : ":" + port);
    }

    public static URI resolveResourceReference(String reference, String serviceOrigin) {
        Objects.requireNonNull(reference, "reference");
        if (reference.startsWith("//")
                || (!reference.startsWith("/")
                        && !reference.startsWith("https://")
                        && !reference.startsWith("http://localhost")
                        && !reference.startsWith("http://127.0.0.1")
                        && !reference.startsWith("http://[::1]"))) {
            throw new IllegalArgumentException(
                    "ODP resource reference must be an origin-relative absolute path or secure absolute URI");
        }
        URI resolved = URI.create(serviceOrigin).resolve(reference);
        if (resolved.getFragment() != null || resolved.getUserInfo() != null) {
            throw new IllegalArgumentException("ODP resource reference cannot contain a fragment or user information");
        }
        requireSecure(resolved);
        return resolved;
    }

    public static URI resolveContinuation(String reference, String serviceOrigin) {
        URI resolved = resolveResourceReference(reference, serviceOrigin);
        if (!deriveServiceOrigin(resolved).equals(deriveServiceOrigin(URI.create(serviceOrigin)))) {
            throw new IllegalArgumentException("ODP continuation reference must remain on the Service origin");
        }
        return resolved;
    }

    public static URI buildOperationUri(
            String endpointBase, OdpOperation operation, String serviceOrigin, String identifier) {
        if (!endpointBase.startsWith("/") || endpointBase.startsWith("//")) {
            throw new IllegalArgumentException("ODP endpoint base must be an origin-relative absolute path");
        }
        String suffix =
                switch (operation) {
                    case LIST_COLLECTIONS -> "/collections";
                    case SEARCH_COLLECTIONS -> "/collections/search";
                    case GET_COLLECTION -> "/collections/" + requireIdentifier(identifier, operation);
                    case LIST_COLLECTION_OFFERINGS ->
                        "/collections/" + requireIdentifier(identifier, operation) + "/offerings";
                    case LIST_OFFERINGS -> "/offerings";
                    case SEARCH_OFFERINGS -> "/offerings/search";
                    case GET_OFFERING -> "/offerings/" + requireIdentifier(identifier, operation);
                };
        if ((operation == OdpOperation.LIST_COLLECTIONS
                        || operation == OdpOperation.SEARCH_COLLECTIONS
                        || operation == OdpOperation.LIST_OFFERINGS
                        || operation == OdpOperation.SEARCH_OFFERINGS)
                && identifier != null) {
            throw new IllegalArgumentException(operation.value() + " does not accept a resource identifier");
        }
        return resolveResourceReference(endpointBase.replaceFirst("/$", "") + suffix, serviceOrigin);
    }

    public static boolean isLocalResourceIdentifier(String value) {
        return value != null
                && !".".equals(value)
                && !"..".equals(value)
                && IDENTIFIER.matcher(value).matches();
    }

    private static String requireIdentifier(String identifier, OdpOperation operation) {
        if (!isLocalResourceIdentifier(identifier)) {
            throw new IllegalArgumentException(operation.value() + " requires a valid local resource identifier");
        }
        return identifier;
    }

    private static void requireSecure(URI uri) {
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("ODP URI must include a host");
        }
        boolean loopback = "localhost".equals(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) // NOPMD - ODP explicitly permits loopback HTTP.
                || "[::1]".equals(uri.getHost())
                || "::1".equals(uri.getHost()); // NOPMD - ODP explicitly permits loopback HTTP.
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
            throw new IllegalArgumentException("ODP URI must use HTTPS except on loopback hosts");
        }
    }
}
