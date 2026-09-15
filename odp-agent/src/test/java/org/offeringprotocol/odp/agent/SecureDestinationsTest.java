package org.offeringprotocol.odp.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecureDestinationsTest {
    /**
     * SEC-08: every destination the IANA special-purpose registries mark as non-public is refused.
     * The IPv6 transition ranges each embed an IPv4 address, so 64:ff9b::a9fe:a9fe is a route to
     * link-local 169.254.169.254 and has to be refused as one.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://0.0.0.1/",
                "https://10.1.2.3/",
                "https://100.64.0.1/",
                "https://127.0.0.1/",
                "https://169.254.169.254/",
                "https://172.16.0.1/",
                "https://192.0.0.1/",
                "https://192.0.2.1/",
                "https://192.31.196.1/",
                "https://192.88.99.1/",
                "https://192.168.1.1/",
                "https://192.175.48.1/",
                "https://198.18.0.1/",
                "https://198.51.100.1/",
                "https://203.0.113.1/",
                "https://224.0.0.1/",
                "https://240.0.0.1/",
                "https://[::1]/",
                "https://[::ffff:169.254.169.254]/",
                "https://[64:ff9b::a9fe:a9fe]/",
                "https://[64:ff9b:1::1]/",
                "https://[100::1]/",
                "https://[2001::1]/",
                "https://[2001:2::1]/",
                "https://[2001:3::1]/",
                "https://[2001:4:112::1]/",
                "https://[2001:10::1]/",
                "https://[2001:20::1]/",
                "https://[2001:30::1]/",
                "https://[2001:db8::1]/",
                "https://[2002::1]/",
                "https://[2620:4f:8000::1]/",
                "https://[5f00::1]/",
                "https://[fc00::1]/",
                "https://[fd00::1]/",
                "https://[fe80::1]/",
                "https://[fec0::1]/",
                "https://[ff00::1]/"
            })
    void refusesEveryNonPublicDestination(String target) {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> SecureDestinations.require(URI.create(target), false));
        assertEquals("ODP request host resolved to a non-public address", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://93.184.216.34/", "https://[2606:2800:220:1:248:1893:25c8:1946]/"})
    void acceptsAPublicDestination(String target) {
        assertDoesNotThrow(() -> SecureDestinations.require(URI.create(target), false));
    }

    @Test
    void reachesLoopbackOnlyForALocalDevelopmentHost() {
        assertDoesNotThrow(() -> SecureDestinations.require(URI.create("https://127.0.0.1:8080/"), true));
        assertDoesNotThrow(() -> SecureDestinations.require(URI.create("https://[::1]:8080/"), true));
        // The allowance is for a host that names the local machine, not for any address that happens
        // to be internal.
        assertThrows(
                IllegalStateException.class, () -> SecureDestinations.require(URI.create("https://10.1.2.3/"), true));
    }

    @Test
    void refusesATargetThatNamesNoHost() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> SecureDestinations.require(URI.create("file:///etc/hosts"), false));
        assertEquals("ODP request target must name a host", failure.getMessage());
    }

    @Test
    void reportsAHostThatDoesNotResolve() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> SecureDestinations.require(URI.create("https://absent.invalid/"), false));
        assertTrue(failure.getMessage().contains("did not resolve"), failure.getMessage());
    }

    @Test
    void buildsTransportsForBothPolicies() {
        assertThrows(
                IllegalStateException.class,
                () -> SecureDestinations.transport(false)
                        .send(java.net.http.HttpRequest.newBuilder(URI.create("https://127.0.0.1/"))
                                .build()));
        assertThrows(
                IllegalStateException.class,
                () -> SecureDestinations.transport(true)
                        .send(java.net.http.HttpRequest.newBuilder(URI.create("https://10.0.0.1/"))
                                .build()));
    }
}
