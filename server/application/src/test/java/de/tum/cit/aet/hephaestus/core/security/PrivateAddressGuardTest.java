package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrivateAddressGuardTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1", // loopback
                "::1", // IPv6 loopback
                "0.0.0.0", // wildcard / "this network"
                "10.0.0.5", // RFC 1918
                "172.16.0.1", // RFC 1918
                "192.168.1.1", // RFC 1918
                "169.254.169.254", // link-local — the cloud metadata endpoint
                "fe80::1", // IPv6 link-local
                "100.64.0.1", // CGNAT (RFC 6598) — k8s pod networking
                "192.0.0.1", // IETF protocol assignments
                "192.0.2.1", // TEST-NET-1
                "198.18.0.1", // benchmarking
                "198.51.100.1", // TEST-NET-2
                "203.0.113.1", // TEST-NET-3
                "240.0.0.1", // Class E reserved
                "255.255.255.255", // broadcast
                "224.0.0.1", // multicast
                "fc00::1", // IPv6 unique-local
                "fd12:3456::1", // IPv6 unique-local
                "64:ff9b::7f00:1", // NAT64 well-known prefix embedding 127.0.0.1
                "2001:db8::1", // documentation
                "::ffff:169.254.169.254", // IPv4-mapped metadata
                "::ffff:127.0.0.1", // IPv4-mapped loopback
                "::ffff:10.0.0.1", // IPv4-mapped private
                "::127.0.0.1", // IPv4-compatible loopback
                "::a9fe:a9fe", // IPv4-compatible metadata
                "2002:7f00:1::", // 6to4 of 127.0.0.1
                "2002:a9fe:a9fe::", // 6to4 of the metadata address
                "2001:0:4136:e378:8000:63bf:3fff:fdd2", // Teredo
                "64:ff9b::a9fe:a9fe", // NAT64 of the metadata address
                "fe80::1", // link-local
                "::", // unspecified
                "::ffff:0:a9fe:a9fe", // IPv4-translated metadata
                "::ffff:0:7f00:1", // IPv4-translated loopback
            })
    void blocksNonPublicAddresses(String literal) throws UnknownHostException {
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName(literal)))
                .as("%s must be treated as non-public (SSRF-unsafe)", literal)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8.8.8.8", // Google DNS
                "1.1.1.1", // Cloudflare DNS
                "140.82.121.4", // github.com range
                "2606:4700:4700::1111", // Cloudflare public IPv6
            })
    void allowsPublicAddresses(String literal) throws UnknownHostException {
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName(literal)))
                .as("%s is public and must be allowed", literal)
                .isFalse();
    }

    @Test
    void cgnatBoundaryIsExactRfc6598Range() throws UnknownHostException {
        // 100.64.0.0/10 = 100.64.0.0 .. 100.127.255.255. 100.63.x and 100.128.x are public.
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName("100.64.0.0")))
                .isTrue();
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName("100.127.255.255")))
                .isTrue();
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName("100.63.255.255")))
                .isFalse();
        assertThat(PrivateAddressGuard.isNonPublic(InetAddress.getByName("100.128.0.0")))
                .isFalse();
    }

    /** Netty keeps an IPv4-mapped answer as IPv6, whose own predicates miss loopback and link-local. */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "10.0.0.1"})
    void blocksAnIpv4MappedAddressThatStaysIpv6(String ipv4) throws UnknownHostException {
        byte[] v4 = InetAddress.getByName(ipv4).getAddress();
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(v4, 0, mapped, 12, 4);
        var address = Inet6Address.getByAddress(null, mapped, -1);

        assertThat(PrivateAddressGuard.isNonPublic(address)).isTrue();
    }
}
