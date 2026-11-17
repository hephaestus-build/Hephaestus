package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostAddressesTest extends BaseUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "0.0.0.0", "255.255.255.255", "::1", "2001:db8::1", "::ffff:10.0.0.1"})
    void shouldParseAnIpLiteral(String literal) {
        assertThat(HostAddresses.literal(literal))
                .map(InetAddress::getHostAddress)
                .isPresent();
    }

    /** Each of these is a name that DNS could answer, or a form that names different addresses on different parsers. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "cafe",
                "beef.dead",
                "example.com",
                "0177.0.0.1",
                "127.1",
                "2130706433",
                "0x7f000001",
                "256.1.1.1",
                "",
                ".:1",
                "..:ab",
                ".::",
                "[::1]"
            })
    void shouldNotTakeANameOrAnAmbiguousNumberForALiteral(String text) {
        assertThat(HostAddresses.literal(text)).isEmpty();
    }
}
