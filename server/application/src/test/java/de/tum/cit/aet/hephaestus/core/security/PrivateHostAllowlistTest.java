package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrivateHostAllowlistTest extends BaseUnitTest {

    private static final PrivateHostAllowlist ALLOWLIST =
            parse(" VLLM.Lab.Test ", "10.20.0.0/16", "192.168.5.9", "fd00:1::/48", "");

    private static PrivateHostAllowlist parse(String... entries) {
        return PrivateHostAllowlist.parse(List.of(entries), List.of());
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @Test
    void shouldPermitListedHostNameIgnoringCase() throws UnknownHostException {
        assertThat(ALLOWLIST.permits("vllm.LAB.test", ip("172.16.4.4"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.20.255.1", "192.168.5.9", "fd00:1::7"})
    void shouldPermitAnyHostNameResolvingIntoListedAddress(String literal) throws UnknownHostException {
        assertThat(ALLOWLIST.permits("other.lab.test", ip(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.21.0.1", "192.168.5.10", "fd00:2::1"})
    void shouldRefuseUnlistedHostNameOutsideListedAddresses(String literal) throws UnknownHostException {
        assertThat(ALLOWLIST.permits("other.lab.test", ip(literal))).isFalse();
    }

    /** A listed host name may resolve anywhere, so this is the widest the list can reach. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "::1",
                "169.254.169.254",
                "::ffff:169.254.169.254", // IPv4-mapped
                "fe80::1",
                "0.0.0.0",
                "224.0.0.1",
                "64:ff9b::a9fe:a9fe", // NAT64 of 169.254.169.254
                "192.0.0.192", // Oracle Cloud IPv4 metadata
                "8.8.8.8", // public
                "100.100.100.200", // Alibaba Cloud metadata, inside 100.64.0.0/10
                "fd00:ec2::254", // AWS IMDS, inside fc00::/7
                "fd00:ec2::23", // AWS EKS Pod Identity Agent
                "fd20:ce::254", // Google Cloud metadata
                "fd00:a9fe:a9fe::1", // Akamai (Linode) metadata
                "fd00:42::42", // Scaleway metadata
                "fd00:c1::a9fe:a9fe", // Oracle Cloud IPv6 metadata
                "253.32.0.206", // IPv4 whose bytes start like fd20:ce::/48
                "a00::1", // IPv6 whose first byte is 10
            })
    void shouldRefuseEveryAddressOutsideThePrivateRangesOrInAMetadataRange(String literal) throws UnknownHostException {
        assertThat(parse("vllm.lab.test").permits("vllm.lab.test", ip(literal))).isFalse();
    }

    @Test
    void shouldNotMatchAnAddressOfTheOtherFamily() throws UnknownHostException {
        PrivateHostAllowlist ranges = parse("10.20.0.0/16", "fd00:1::/48");

        assertThat(ranges.permits("x", ip("a14::1"))).isFalse(); // first bytes 10.20
        assertThat(ranges.permits("x", ip("253.0.0.1"))).isFalse(); // first byte 0xfd
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://vllm.lab.test",
                "vllm.lab.test:8000",
                "*.lab.test",
                "10.0.0.0/33",
                "10.1", // read as 10.0.0.1
                "10.20.0/24",
                // Outside every private range, also when the bytes would match another family's range.
                "0.0.0.0/0",
                "::/0",
                "8.8.8.0/24",
                "169.254.0.0/16",
                "253.0.0.0/16",
                "a00::/48",
                // Wider than one network.
                "10.0.0.0/8",
                "172.16.0.0/12",
                "10.20.0.0/15",
                "fc00::/7",
                "fd00::/8",
                "fd00:1::/47",
                // Inside a metadata range, so it would never connect.
                "100.100.100.200",
                "fd00:ec2::/48",
                "fd00:c1::/64",
            })
    void shouldRejectEntryThatIsNoNarrowPrivateHostAddressOrRange(String entry) {
        assertThatThrownBy(() -> parse(entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectRangeThatCoversThisServersOwnAddress() throws UnknownHostException {
        List<InetAddress> own = List.of(ip("127.0.0.1"), ip("172.29.47.5"));

        assertThatThrownBy(() -> PrivateHostAllowlist.parse(List.of("172.29.0.0/16"), own))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("172.29.47.5");
        assertThat(PrivateHostAllowlist.parse(List.of("172.29.48.0/24", "vllm.lab.test"), own)
                        .permits("x", ip("172.29.48.9")))
                .isTrue();
    }
}
