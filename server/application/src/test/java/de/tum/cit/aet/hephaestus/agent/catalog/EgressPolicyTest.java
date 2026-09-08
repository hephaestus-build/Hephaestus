package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.LlmProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

class EgressPolicyTest extends BaseUnitTest {

    @Mock
    private InstanceLlmSettingsRepository settingsRepository;

    private static LlmProperties properties(boolean allowLoopback) {
        return new LlmProperties(
                "", new LlmProperties.Egress(allowLoopback), new LlmProperties.Fx(LlmProperties.ECB_DAILY_URL));
    }

    private EgressPolicy loopbackBlocked() {
        return new EgressPolicy(settingsRepository, properties(false));
    }

    private EgressPolicy loopbackAllowed() {
        return new EgressPolicy(settingsRepository, properties(true));
    }

    private void stubNoSettingsRow() {
        lenient().when(settingsRepository.findById((short) 1)).thenReturn(java.util.Optional.empty());
    }

    private void stubAllowlist(String allowlist) {
        InstanceLlmSettings settings = new InstanceLlmSettings();
        settings.setId((short) 1);
        settings.setAllowedEgressHosts(allowlist);
        when(settingsRepository.findById((short) 1)).thenReturn(java.util.Optional.of(settings));
    }

    @Nested
    class PrivateAndLinkLocalAddresses {

        @ParameterizedTest
        @DisplayName("cloud metadata endpoint and RFC1918 private ranges are always blocked, allowlist or not")
        @ValueSource(
                strings = {
                    "169.254.169.254", // cloud metadata endpoint (link-local)
                    "10.0.0.1", // 10/8
                    "10.255.255.255",
                    "172.16.0.1", // 172.16/12
                    "172.31.255.255",
                    "192.168.0.1", // 192.168/16
                    "192.168.255.255",
                    "fc00::1", // IPv6 unique-local
                    "fe80::1", // IPv6 link-local
                })
        void blocksPrivateAndLinkLocalHosts(String host) {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackBlocked();

            assertThatThrownBy(() -> policy.validate("https://" + wrapIfIpv6(host) + "/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }

        @ParameterizedTest
        @DisplayName("IANA special-purpose ranges (via PrivateAddressGuard) are blocked too — "
                + "multicast, CGNAT, NAT64, and the benchmarking/TEST-NET ranges routinely front internal services")
        @ValueSource(
                strings = {
                    "224.0.0.1", // IPv4 multicast
                    "ff02::1", // IPv6 multicast
                    "100.64.0.1", // 100.64.0.0/10 carrier-grade NAT
                    "100.127.255.255",
                    "0.0.0.0", // "this network"
                    "192.0.0.1", // IETF protocol assignments
                    "192.0.2.1", // TEST-NET-1
                    "198.18.0.1", // benchmarking
                    "198.51.100.1", // TEST-NET-2
                    "203.0.113.1", // TEST-NET-3
                    "240.0.0.1", // reserved (Class E)
                    "255.255.255.255", // broadcast
                    "64:ff9b::1", // NAT64 well-known prefix (RFC 6052) — embeds IPv4, incl. loopback
                    "2001:db8::1", // IPv6 documentation range
                })
        void blocksReservedAndSpecialPurposeRanges(String host) {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackBlocked();

            assertThatThrownBy(() -> policy.validate("https://" + wrapIfIpv6(host) + "/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }
    }

    @Nested
    class Loopback {

        @ParameterizedTest
        @ValueSource(strings = {"localhost", "127.0.0.1"})
        @DisplayName("loopback is blocked by default (hephaestus.llm.egress.allow-loopback=false)")
        void blocksLoopbackByDefault(String host) {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackBlocked();

            assertThatThrownBy(() -> policy.validate("http://" + host + ":11434/v1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        // localhost resolves through InetAddress.getAllByName, satisfied from /etc/hosts — offline-safe.
        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "localhost"})
        @DisplayName("https to loopback is ALSO blocked by default — the flag gates loopback outright, not just http")
        void blocksHttpsLoopbackByDefaultToo(String host) {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackBlocked();

            assertThatThrownBy(() -> policy.validate("https://" + host + "/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }

        @ParameterizedTest
        @ValueSource(strings = {"localhost", "127.0.0.1", "[::1]"})
        @DisplayName("plain http to loopback is allowed when hephaestus.llm.egress.allow-loopback=true")
        void allowsHttpLoopbackWhenFlagEnabled(String host) {
            EgressPolicy policy = loopbackAllowed();

            assertThatCode(() -> policy.validate("http://" + host + ":11434/v1"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class SchemeEnforcement {

        @Test
        @DisplayName("plain http to a non-loopback host is rejected regardless of the loopback flag")
        void rejectsHttpToPublicHost() {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackAllowed();

            assertThatThrownBy(() -> policy.validate("http://8.8.8.8/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @NullSource
        @ValueSource(strings = {"   ", "https:///v1", "not a url at all"})
        void rejectsEveryBaseUrlWithoutAUsableHostWithTheSameMessage(String baseUrl) {
            assertThatThrownBy(() -> loopbackBlocked().validate(baseUrl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }
    }

    @Nested
    class CredentialAndQuerySmuggling {

        @Test
        @DisplayName("userinfo in the base URL is rejected (credential smuggled in the URL itself)")
        void rejectsUserinfo() {
            assertThatThrownBy(() -> loopbackBlocked().validate("https://user:secret@gw.example.com/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider URLs must not contain credentials or query parameters.");
        }

        @Test
        @DisplayName("a query string in the base URL is rejected (e.g. gateway URLs with ?api-key=SECRET)")
        void rejectsQueryString() {
            assertThatThrownBy(() -> loopbackBlocked().validate("https://gw.example.com/v1?api-key=SECRET"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider URLs must not contain credentials or query parameters.");
        }

        @Test
        @DisplayName("a fragment in the base URL is rejected")
        void rejectsFragment() {
            assertThatThrownBy(() -> loopbackBlocked().validate("https://gw.example.com/v1#token=SECRET"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider URLs must not contain credentials or query parameters.");
        }
    }

    @Nested
    class Allowlist {

        // IP literals throughout: InetAddress.getAllByName() parses a numeric address locally without a
        // DNS round-trip, so these stay deterministic/offline-safe (assertPublicAddress runs — and must
        // succeed — before assertAllowlisted is ever reached).

        @Test
        @DisplayName("an empty/blank allowlist permits any public host")
        void emptyAllowlistPermitsAnyPublicHost() {
            stubAllowlist("");

            assertThatCode(() -> loopbackBlocked().validate("https://8.8.8.8/v1/openai"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("no settings row at all (not seeded) behaves like an empty allowlist")
        void missingSettingsRowPermitsAnyPublicHost() {
            stubNoSettingsRow();

            assertThatCode(() -> loopbackBlocked().validate("https://8.8.8.8/v1"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a host present in the allowlist (case-insensitive) is permitted")
        void permitsAHostListedInTheAllowlistIgnoringCase() {
            stubAllowlist("8.8.8.8,[2606:4700:4700::ABCD]\n1.1.1.1");

            assertThatCode(() -> loopbackBlocked().validate("https://[2606:4700:4700::abcd]/v1"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a host absent from a non-blank allowlist is rejected")
        void rejectsAHostAbsentFromANonBlankAllowlist() {
            stubAllowlist("1.1.1.1,9.9.9.9");

            assertThatThrownBy(() -> loopbackBlocked().validate("https://8.8.8.8/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("8.8.8.8")
                    .hasMessageContaining("not in the allowed list");
        }
    }

    @Nested
    class HostnameResolution {

        @Test
        @DisplayName("a hostname that fails to resolve is rejected rather than propagating UnknownHostException")
        void unresolvableHostnameIsRejected() {
            stubNoSettingsRow();
            EgressPolicy policy = loopbackBlocked();

            assertThatThrownBy(() -> policy.validate("https://this-host-does-not-exist.invalid.example.test/v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Provider host must be a public HTTPS URL");
        }
    }

    private static String wrapIfIpv6(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
