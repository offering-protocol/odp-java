package org.offeringprotocol.odp.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DirectoryOriginsTest {
    /** RFC 3986 6.2.3: a written-out default port names the same origin as an omitted one. */
    @Test
    void readsAWrittenOutDefaultPortAsTheSameOrigin() {
        assertTrue(DirectoryOrigins.sameOrigin(
                URI.create("https://sandbox.inflowpay.ai:443/v1/services/search"),
                URI.create("https://sandbox.inflowpay.ai")));
        assertTrue(DirectoryOrigins.sameOrigin(URI.create("http://x.example:80/a"), URI.create("http://x.example")));
        assertTrue(DirectoryOrigins.sameOrigin(URI.create("HTTPS://X.Example/a"), URI.create("https://x.example")));
    }

    @Test
    void readsEveryOtherDifferenceAsAnotherOrigin() {
        URI canonical = URI.create("https://sandbox.inflowpay.ai");
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("https://elsewhere.example"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("http://sandbox.inflowpay.ai"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("https://sandbox.inflowpay.ai:8443"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("https://user@sandbox.inflowpay.ai"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("/v1/services/search"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(canonical, URI.create("/v1/services/search")));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("https:/v1/services/search"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(canonical, URI.create("https:/v1/services/search")));
        assertFalse(DirectoryOrigins.sameOrigin(canonical, URI.create("https://user@sandbox.inflowpay.ai")));
        assertFalse(DirectoryOrigins.sameOrigin(URI.create("mailto:ops@example.com"), canonical));
        assertFalse(DirectoryOrigins.sameOrigin(canonical, URI.create("mailto:ops@example.com")));
    }

    @Test
    void acceptsTheAsciiSerializationOfASecureOrigin() {
        DirectoryOrigins.requireServiceOrigin("https://plants.example");
        DirectoryOrigins.requireServiceOrigin("https://xn--caf-dma.example");
        DirectoryOrigins.requireServiceOrigin("https://8.8.8.8");
        DirectoryOrigins.requireServiceOrigin("https://[2001:4860:4860::8888]");
    }

    /** A value an Agent would have to repair before using it is not an origin the directory may send. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://plants.example",
                "https://Plants.Example",
                "https://LOCALHOST",
                "https://plants.example:443",
                "https://plants.example/",
                "https://plants.example/catalog",
                "https://user@plants.example",
                "https://plants.example?q=1",
                "https://plants.example#top",
                "https://",
                "ftp://plants.example",
                "plants.example"
            })
    void rejectsAnOriginAnAgentWouldHaveToRepair(String value) {
        assertThrows(IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin(value));
    }

    @Test
    void rejectsAnOriginThatIsMissingOrUnparseable() {
        assertThrows(IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin(null));
        assertThrows(IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin("  "));
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectoryOrigins.requireServiceOrigin("https://plants.example/" + "a".repeat(2048)));
        assertThrows(
                IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin("https://a b.example"));
        assertThrows(
                IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin("https://999.888.777.666"));
    }

    /** A destination the public internet does not route is not somewhere an Agent may be sent. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://localhost",
                "https://service.localhost",
                "https://127.0.0.1",
                "https://127.9.9.9",
                "https://0.0.0.0",
                "https://10.1.2.3",
                "https://172.16.9.9",
                "https://192.168.1.1",
                "https://169.254.169.254",
                "https://224.0.0.1",
                "https://[::1]",
                "https://[fe80::1]",
                "https://[fc00::1]",
                "https://[ff02::1]"
            })
    void rejectsADestinationThePublicInternetDoesNotRoute(String value) {
        assertEquals(
                "Directory result service_origin must name a public host",
                assertThrows(IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin(value))
                        .getMessage());
    }

    /** An IPv6 transition range embeds an IPv4 address, so a public-looking one can still be internal. */
    @Test
    void rejectsAnAddressThatReachesAnInternalOneThroughATransitionRange() {
        for (String value : new String[] {
            "https://[64:ff9b::a9fe:a9fe]",
            "https://[::ffff:127.0.0.1]",
            "https://[2002:a9fe:a9fe::1]",
            "https://[2001::1]",
            "https://100.64.0.1"
        }) {
            assertEquals(
                    "Directory result service_origin must name a public host",
                    assertThrows(IllegalArgumentException.class, () -> DirectoryOrigins.requireServiceOrigin(value))
                            .getMessage());
        }
    }
}
