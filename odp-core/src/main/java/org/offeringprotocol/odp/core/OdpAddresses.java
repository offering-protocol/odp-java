package org.offeringprotocol.odp.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The IANA special-purpose address registries (RFC 6890 and its successors), as a single question:
 * is this address one the public internet routes? SEC-08 turns on the answer, so both the Agent
 * checking where it is about to connect and the client reading an address out of a third party's
 * document ask it here rather than each keeping its own idea of what is internal.
 *
 * <p>The IPv6 transition ranges matter as much as the obvious ones: each embeds an IPv4 address, so
 * without them an address of {@code 64:ff9b::a9fe:a9fe} reaches link-local 169.254.169.254.
 */
@SuppressWarnings("PMD.AvoidUsingHardCodedIP") // The registries this enforces are literal addresses.
public final class OdpAddresses {
    private static final int OCTET = 0xFF;
    private static final int IPV6_BYTES = 16;
    private static final int IPV4_MAPPED_PREFIX_BYTES = 10;
    private static final List<Prefix> NON_PUBLIC = List.of(
            prefix("0.0.0.0", 8),
            prefix("10.0.0.0", 8),
            prefix("100.64.0.0", 10),
            prefix("127.0.0.0", 8),
            prefix("169.254.0.0", 16),
            prefix("172.16.0.0", 12),
            prefix("192.0.0.0", 24),
            prefix("192.0.2.0", 24),
            prefix("192.31.196.0", 24),
            prefix("192.88.99.0", 24),
            prefix("192.168.0.0", 16),
            prefix("192.175.48.0", 24),
            prefix("198.18.0.0", 15),
            prefix("198.51.100.0", 24),
            prefix("203.0.113.0", 24),
            prefix("224.0.0.0", 4),
            prefix("240.0.0.0", 4),
            prefix("::", 96),
            prefix("64:ff9b::", 96),
            prefix("64:ff9b:1::", 48),
            prefix("100::", 64),
            prefix("2001::", 32),
            prefix("2001:2::", 48),
            prefix("2001:3::", 32),
            prefix("2001:4:112::", 48),
            prefix("2001:10::", 28),
            prefix("2001:20::", 28),
            prefix("2001:30::", 28),
            prefix("2001:db8::", 32),
            prefix("2002::", 16),
            prefix("2620:4f:8000::", 48),
            prefix("5f00::", 16),
            prefix("fc00::", 7),
            prefix("fe80::", 10),
            prefix("fec0::", 10),
            prefix("ff00::", 8));

    private OdpAddresses() {}

    /** True when the address falls in no special-purpose range, so the public internet routes it. */
    public static boolean isPublic(InetAddress address) {
        byte[] bytes = unmap(address.getAddress());
        for (Prefix candidate : NON_PUBLIC) {
            if (candidate.contains(bytes)) {
                return false;
            }
        }
        return true;
    }

    /** An IPv4-mapped IPv6 address is the IPv4 address it carries, and is judged as one. */
    private static byte[] unmap(byte[] bytes) {
        if (!isIpv4Mapped(bytes)) {
            return bytes;
        }
        return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != IPV6_BYTES) {
            return false;
        }
        for (int index = 0; index < IPV4_MAPPED_PREFIX_BYTES; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[IPV4_MAPPED_PREFIX_BYTES] & OCTET) == OCTET
                && (bytes[IPV4_MAPPED_PREFIX_BYTES + 1] & OCTET) == OCTET;
    }

    private static Prefix prefix(String network, int bits) {
        try {
            return new Prefix(InetAddress.getByName(network).getAddress(), bits);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Unable to read the special-purpose address registry", exception);
        }
    }

    private record Prefix(byte[] network, int bits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int whole = bits / Byte.SIZE;
            for (int index = 0; index < whole; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            int remainder = bits % Byte.SIZE;
            if (remainder == 0) {
                return true;
            }
            int mask = (OCTET << (Byte.SIZE - remainder)) & OCTET;
            return (address[whole] & mask) == (network[whole] & mask);
        }
    }
}
