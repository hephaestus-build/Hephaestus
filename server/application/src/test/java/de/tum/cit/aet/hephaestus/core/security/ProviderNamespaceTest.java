package de.tum.cit.aet.hephaestus.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProviderNamespaceTest extends BaseUnitTest {

    @ParameterizedTest
    @CsvSource({
        "OIDC, https://kc.example.org/realms/school, HTTPS://KC.example.org:443/realms/school/, true",
        "OIDC, https://kc.example.org/realms/school, https://kc.example.org/realms/other, false",
        "OIDC, https://kc.example.org/realms/school, https://kc.example.org, false",
        "OIDC, https://kc.example.org, https://kc.example.org/, true",
        "GITLAB, https://gitlab.example.org, https://GitLab.example.org/, true",
        "OUTLINE, https://wiki.example.org, https://wiki.example.org:443, true",
        "GITLAB, https://gitlab.example.org, https://gitlab.example.com, false",
    })
    void shouldKeepTheIssuerPathOnlyWhenTheProviderIsOidc(
            String type, String serverUrl, String otherServerUrl, boolean same) {
        assertThat(ProviderNamespace.same(type, serverUrl, otherServerUrl)).isEqualTo(same);
    }

    @ParameterizedTest
    @CsvSource({"OIDC, not a url", "GITLAB, not a url"})
    void shouldMatchNothingWhenTheUrlHasNoOrigin(String type, String serverUrl) {
        assertThat(ProviderNamespace.of(type, serverUrl)).isEmpty();
        assertThat(ProviderNamespace.same(type, serverUrl, serverUrl)).isFalse();
    }
}
