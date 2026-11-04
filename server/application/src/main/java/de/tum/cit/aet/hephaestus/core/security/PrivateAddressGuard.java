package de.tum.cit.aet.hephaestus.core.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Classifies resolved addresses before outbound connections to user-supplied hosts. */
public final class PrivateAddressGuard {

    private PrivateAddressGuard() {}

    public static boolean isNonPublic(InetAddress address) {
        InetAddress addr = unmapped(address);
        return (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress()
                || isUniqueLocalIpv6(addr)
                || isReservedRange(addr));
    }

    /**
     * The IPv4 address that an IPv4-mapped IPv6 address (::ffff:0:0/96) carries. Java's parser returns it as IPv4, but
     * Netty keeps the IPv6 form, whose predicates then miss loopback and link-local.
     */
    public static InetAddress unmapped(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length != 16) return address;
        for (int i = 0; i < 10; i++) if (b[i] != 0) return address;
        if ((b[10] & 0xFF) != 0xFF || (b[11] & 0xFF) != 0xFF) return address;
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** fc00::/7 — IPv6 unique-local; not covered by {@link InetAddress#isSiteLocalAddress()}. */
    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** Additional IANA special-purpose ranges not covered by {@link InetAddress} predicates. */
    private static boolean isReservedRange(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF;
            if (b0 == 0) return true; // 0.0.0.0/8 "this network"
            if (b0 == 100 && (b1 & 0xC0) == 0x40) return true; // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 192 && b1 == 0 && b2 == 0) return true; // 192.0.0.0/24 IETF protocol assignments
            if (b0 == 192 && b1 == 0 && b2 == 2) return true; // 192.0.2.0/24 TEST-NET-1
            if (b0 == 198 && (b1 & 0xFE) == 18) return true; // 198.18.0.0/15 benchmarking
            if (b0 == 198 && b1 == 51 && b2 == 100) return true; // 198.51.100.0/24 TEST-NET-2
            if (b0 == 203 && b1 == 0 && b2 == 113) return true; // 203.0.113.0/24 TEST-NET-3
            return b0 >= 240; // 240.0.0.0/4 reserved (Class E) + 255.255.255.255 broadcast
        }
        if (b.length == 16) {
            // These forms carry an IPv4 address that the classic predicates do not read: ::/96 (IPv4-compatible),
            // 2002::/16 (6to4), 2001::/32 (Teredo) and ::ffff:0:0/96 translated forms. None is a public Git host.
            boolean highZero = true;
            for (int i = 0; i < 12; i++) highZero &= b[i] == 0;
            if (highZero) return true;
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) return true;
            // ::ffff:0:0/96, the IPv4-translated form.
            boolean translated = (b[8] & 0xFF) == 0xFF && (b[9] & 0xFF) == 0xFF && b[10] == 0 && b[11] == 0;
            for (int i = 0; i < 8; i++) translated &= b[i] == 0;
            if (translated) return true;
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && b[2] == 0 && b[3] == 0) return true;
            // 64:ff9b::/32 includes standardized NAT64 prefixes.
            if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xff && (b[3] & 0xFF) == 0x9b) {
                return true;
            }
            // 2001:db8::/32 documentation
            return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0d && (b[3] & 0xFF) == 0xb8;
        }
        return false;
    }
}
