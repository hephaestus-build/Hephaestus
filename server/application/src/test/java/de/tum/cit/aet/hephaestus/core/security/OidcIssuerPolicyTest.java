package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidcIssuerPolicyTest extends BaseUnitTest {

    @Test
    void shouldApproveOnlyTheExactIssuerIncludingItsRealmAndTrailingSlash() {
        var policy = new OidcIssuerPolicy(Set.of(" https://identity.example.com/realms/team/ "));

        assertThat(policy.allows("https://identity.example.com/realms/team/")).isTrue();
        assertThat(policy.allows("https://identity.example.com/realms/team")).isFalse();
        assertThat(policy.allows("https://identity.example.com/realms/other/")).isFalse();
        assertThat(policy.allows("https://identity.example.com")).isFalse();
        assertThat(policy.allows("https://IDENTITY.example.com/realms/team/")).isFalse();
    }

    @Test
    void shouldDisableOrganizationalProvidersWhenNoIssuerIsApproved() {
        assertThat(new OidcIssuerPolicy(Set.of()).allows("https://identity.example.com/realms/team"))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://identity.example.com/realms/team",
                "https://user:password@identity.example.com/realms/team",
                "https://identity.example.com/realms/team?realm=other",
                "https://identity.example.com/realms/team#other",
                "https://localhost/realms/team",
                "https://127.0.0.1/realms/team",
                "https://2130706433/realms/team",
                "https://169.254.169.254/metadata",
                "https://[fd00::1]/realms/team",
                "https://identity.internal/realms/team",
                "https:/realms/team",
                "https:///realms/team",
                "not a URL"
            })
    void shouldRejectUnsafeIssuerConfiguration(String issuer) {
        assertThatThrownBy(() -> new OidcIssuerPolicy(Set.of(issuer))).isInstanceOf(IllegalArgumentException.class);
    }
}
