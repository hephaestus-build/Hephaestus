package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerUrlValidatorTest extends BaseUnitTest {

    @BeforeAll
    static void declarePublicHosts() {
        OfflineNetwork.answer("gitlab.com", OfflineNetwork.PUBLIC_ADDRESS);
        OfflineNetwork.answer("git.mycompany.org", OfflineNetwork.PUBLIC_ADDRESS);
    }

    @Nested
    class SchemeValidation {

        // All non-HTTPS schemes reject at the single scheme guard, before any host/path check.
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "http://gitlab.example.com",
                    "ftp://gitlab.example.com",
                    "gopher://gitlab.example.com",
                    "file:///etc/passwd",
                })
        void rejectsNonHttpsScheme(String url) {
            assertThatThrownBy(() -> ServerUrlValidator.validate(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
        }
    }

    @Nested
    class HostnameValidation {

        @Test
        void rejectsLocalhost() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://localhost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        void rejectsLoopbackIp() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://127.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        void rejectsIpv6Loopback() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[::1]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        void rejectsZeroAddress() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://0.0.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsCloudMetadataIp() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://169.254.169.254"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback or reserved");
        }

        @Test
        void rejectsGoogleMetadataHostname() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://metadata.google.internal"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        void rejectsGoogMetadataHostname() {
            // Pins the remaining blocked hostname (metadata.goog) so every deny-list member is covered.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://metadata.goog"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }

        @Test
        void rejectsPrivateIpRange() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://10.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("site-local");
        }

        @Test
        void rejectsLinkLocalIpRange() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://169.254.1.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("link-local");
        }
    }

    @Nested
    class NonCanonicalNumericHostBypass {

        // 2130706433==127.0.0.1, 2852039166==169.254.169.254, 3232235521==192.168.0.1 — all resolve to
        // loopback/metadata/private targets but share the single non-canonical-numeric branch.
        @ParameterizedTest
        @ValueSource(strings = {"https://2130706433", "https://2852039166", "https://3232235521"})
        void rejectsDecimalIntegerHost(String url) {
            assertThatThrownBy(() -> ServerUrlValidator.validate(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-canonical numeric host");
        }

        @Test
        void rejectsHexEncodedIp() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://0x7f000001"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-canonical numeric host");
        }

        @Test
        void rejectsOctalEncodedIp() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://0177.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-canonical numeric host");
        }

        // The 0177-style octal form above reaches the dotted-quad canonicality check because URI parses
        // it as a reg-name. The shorthand / out-of-range forms below are rejected EARLIER: java.net.URI
        // returns a null host for an IPv4-shaped authority that isn't four in-range octets, so validate()
        // fails them at the "valid hostname" guard. Either way they must never slip through — this pins
        // that the dangerous shorthands are rejected, regardless of which guard fires.

        @Test
        void rejectsLoopbackShorthand() {
            // "127.1" expands to 127.0.0.1 (loopback) in many resolvers — the canonical SSRF shorthand.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://127.1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsThreeOctetShorthand() {
            // "1.2.3" expands to 1.2.0.3 — fewer than four octets must not be accepted as a literal host.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://1.2.3"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsOctetAbove255() {
            // 256 is out of range; must not be accepted as a host literal.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://256.1.1.1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsLeadingZeroOctet() {
            // "010.0.0.1" keeps a non-null host (a 3-digit reg-name octet), so it reaches the dotted-quad
            // check — and a leading-zero octet is octal-ambiguous (010 == 8 in some resolvers), so it must
            // be rejected as non-canonical, not silently accepted as 10.0.0.1.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://010.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-canonical numeric host");
        }

        @Test
        void stillAcceptsCanonicalPublicIpv4() {
            assertThatCode(() -> ServerUrlValidator.validate("https://8.8.8.8")).doesNotThrowAnyException();
        }

        @Test
        void stillAcceptsCanonicalPublicIpv4WithAZeroOctet() {
            // Boundary guard must use length > 1, not >= 1: a single "0" octet is canonical, so >= 1
            // would wrongly reject legitimate public IPs. (8.8.8.8 above has no zero octet to exercise it.)
            assertThatCode(() -> ServerUrlValidator.validate("https://8.0.8.8")).doesNotThrowAnyException();
        }

        @Test
        void stillAcceptsCanonicalPublicIpv4WithMaximumOctet() {
            assertThatCode(() -> ServerUrlValidator.validate("https://8.8.8.255"))
                    .doesNotThrowAnyException();
        }

        @Test
        void stillAcceptsPublicIpv6() {
            assertThatCode(() -> ServerUrlValidator.validate("https://[2001:db8::1]"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class PrivateIpv6Bypass {

        // IPv4-mapped addresses (::ffff:…) have no explicit check — they pass only because getByName
        // canonicalizes them to IPv4, so isLoopback/isSiteLocalAddress fire. (ULA rationale is in the source.)

        @Test
        void rejectsIpv6Wildcard() {
            // "[::]" is the only input that reaches the isAnyLocalAddress() guard — IPv4 0.0.0.0
            // short-circuits earlier in the blocked-IP set.
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[::]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("wildcard");
        }

        @Test
        void rejectsIpv6UniqueLocalFc00() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[fc00::1]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unique-local");
        }

        @Test
        void rejectsIpv6UniqueLocalFd00() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[fd12:3456::1]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unique-local");
        }

        @Test
        void rejectsIpv4MappedLoopback() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[::ffff:127.0.0.1]"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsIpv4MappedPrivate() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://[::ffff:10.0.0.1]"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TldValidation {

        @Test
        void rejectsLocalTld() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://gitlab.local"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private TLD");
        }

        @Test
        void rejectsInternalTld() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://gitlab.internal"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private TLD");
        }

        @Test
        void rejectsLocalhostTld() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://myapp.localhost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private TLD");
        }
    }

    @Nested
    class StructureValidation {

        @Test
        void rejectsUrlWithUserinfo() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://evil@gitlab.example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        void rejectsUrlWithPath() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("https://gitlab.example.com/redirect"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("path");
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://gitlab.example.com?next=internal", "https://gitlab.example.com#fragment"})
        void rejectsQueryAndFragment(String url) {
            assertThatThrownBy(() -> ServerUrlValidator.validate(url))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("query or fragment");
        }

        @Test
        void rejectsBlankUrl() {
            assertThatThrownBy(() -> ServerUrlValidator.validate(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void rejectsNullUrl() {
            assertThatThrownBy(() -> ServerUrlValidator.validate(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        void rejectsMalformedUrl() {
            assertThatThrownBy(() -> ServerUrlValidator.validate("not a url at all"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class ValidUrls {

        @Test
        void acceptsGitLabCom() {
            assertThatCode(() -> ServerUrlValidator.validate("https://gitlab.com"))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsSelfHostedGitLab() {
            assertThatCode(() -> ServerUrlValidator.validate("https://gitlab.example.com"))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsSubdomainGitLab() {
            assertThatCode(() -> ServerUrlValidator.validate("https://git.mycompany.org"))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsUrlWithTrailingSlash() {
            assertThatCode(() -> ServerUrlValidator.validate("https://gitlab.example.com/"))
                    .doesNotThrowAnyException();
        }

        @Test
        void acceptsUrlWithPort() {
            assertThatCode(() -> ServerUrlValidator.validate("https://gitlab.example.com:8443"))
                    .doesNotThrowAnyException();
        }
    }
}
