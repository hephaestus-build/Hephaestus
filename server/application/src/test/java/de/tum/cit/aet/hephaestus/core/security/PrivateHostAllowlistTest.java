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
            PrivateHostAllowlist.parse(List.of(" VLLM.Lab.Test ", "10.20.0.0/16", "192.168.5.9", "fd00:1::/32", ""));

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
                "100.100.100.200", // Alibaba Cloud metadata, inside 100.64.0.0/10
                "fd00:ec2::254", // AWS IPv6 metadata, inside fc00::/7
                "64:ff9b::a9fe:a9fe", // NAT64 of 169.254.169.254
                "192.0.0.192", // Oracle Cloud metadata
                "8.8.8.8", // public: a listed name must not turn it into a "private host"
            })
    void shouldRefuseEveryAddressOutsideThePrivateRangesEvenWhenListed(String literal) throws UnknownHostException {
        PrivateHostAllowlist everything = PrivateHostAllowlist.parse(List.of("vllm.lab.test", "0.0.0.0/0", "::/0"));

        assertThat(everything.permits("vllm.lab.test", ip(literal))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://vllm.lab.test", "vllm.lab.test:8000", "*.lab.test", "10.0.0.0/33"})
    void shouldRejectEntryThatIsNoHostNameAddressOrRange(String entry) {
        assertThatThrownBy(() -> PrivateHostAllowlist.parse(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
