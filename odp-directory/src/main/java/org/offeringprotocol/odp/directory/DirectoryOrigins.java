package org.offeringprotocol.odp.directory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.offeringprotocol.odp.core.OdpAddresses;

/**
 * Origin rules for the values a directory hands back. A directory result is an ordinary HTTP
 * document written by a third party: an Agent connects to whatever {@code service_origin} says, so
 * the value is held to the same shape a Service Origin has and to a destination that is routable on
 * the public internet.
 */
final class DirectoryOrigins {
    private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$");
    private static final int MAXIMUM_ORIGIN_CHARACTERS = 2048;

    private DirectoryOrigins() {}

    /** Compares two origins the way RFC 3986 6.2.3 does, so a written-out default port still matches. */
    static boolean sameOrigin(URI left, URI right) {
        return left.getScheme() != null
                && right.getScheme() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost() != null
                && right.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right)
                && left.getUserInfo() == null
                && right.getUserInfo() == null;
    }

    private static int port(URI value) {
        if (value.getPort() != -1) {
            return value.getPort();
        }
        return "http".equalsIgnoreCase(value.getScheme()) ? 80 : 443;
    }

    /**
     * A Service Origin as ODP writes one: the ASCII serialization of a secure origin, and nothing
     * else. A value carrying a path, a port, a case the Agent would have to fold, or an address the
     * public internet does not route is not an origin an Agent can be pointed at.
     */
    static void requireServiceOrigin(String value) {
        if (value == null || value.isBlank() || value.length() > MAXIMUM_ORIGIN_CHARACTERS) {
            throw new IllegalArgumentException("Directory result service_origin is missing");
        }
        URI origin;
        try {
            origin = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Directory result service_origin is not a URL", exception);
        }
        if (!"https".equals(origin.getScheme())
                || origin.getUserInfo() != null
                || origin.getHost() == null
                || origin.getPort() != -1
                || !origin.getRawPath().isEmpty()
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || !value.equals("https://" + origin.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Directory result service_origin must be a canonical HTTPS origin");
        }
        requirePublicHost(origin.getHost());
    }

    private static void requirePublicHost(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(name) || name.endsWith(".localhost")) {
            throw new IllegalArgumentException("Directory result service_origin must name a public host");
        }
        boolean bracketed = name.startsWith("[");
        if (!bracketed && !IPV4.matcher(name).matches()) {
            // A name is left to the Agent's own destination policy, which resolves it at the moment
            // it connects; nothing is resolved here.
            return;
        }
        // The value is a literal by now — URI would not have handed back a host otherwise — so this
        // reads the address rather than resolving a name.
        InetAddress address;
        try {
            address = InetAddress.getByName(bracketed ? name.substring(1, name.length() - 1) : name);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Directory result service_origin is not a usable address", exception);
        }
        if (!OdpAddresses.isPublic(address)) {
            throw new IllegalArgumentException("Directory result service_origin must name a public host");
        }
    }
}
