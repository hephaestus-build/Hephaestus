package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityProviderCatalog;
import de.tum.cit.aet.hephaestus.core.auth.web.IdentityProviderDiscoveryController.IdentityProviderViewDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * Unit coverage for the OAuth-registration → discovery mapping in {@link IdentityProviderDiscoveryController}: the
 * provider type comes from the configured {@code login_provider} row only, and {@code baseUrlOf} reconstructs
 * scheme://host[:port], incl. the host-less fallback.
 */
class IdentityProviderDiscoveryControllerTest extends BaseUnitTest {

    private static ClientRegistration registration(String authorizationUri) {
        return registration("p", authorizationUri);
    }

    private static ClientRegistration registration(String registrationId, String authorizationUri) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId("client")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/" + registrationId)
                .authorizationUri(authorizationUri)
                .tokenUri("https://example.com/token")
                .build();
    }

    @Test
    @DisplayName("a self-hosted GitLab on a non-default port keeps the port in baseUrl")
    void selfHostedGitlabWithPort() {
        ClientRegistration reg = registration("https://gitlab.example.com:8443/oauth/authorize");
        assertThat(IdentityProviderDiscoveryController.baseUrlOf(reg)).isEqualTo("https://gitlab.example.com:8443");
    }

    @Test
    @DisplayName("GitLab.com (default port) has a port-less origin")
    void gitlabDotComDefaultPort() {
        ClientRegistration reg = registration("https://gitlab.com/oauth/authorize");
        assertThat(IdentityProviderDiscoveryController.baseUrlOf(reg)).isEqualTo("https://gitlab.com");
    }

    @Test
    @DisplayName("the login_provider row's type is the only source — an Outline instance never reads as GitLab")
    void rowTypeIsTheProviderType() {
        // A self-hosted Outline's /oauth/authorize is URL-shaped exactly like a GitLab's; only the row
        // type can classify it. Misclassifying as GITLAB would render a public login button for a
        // link-only provider (the SPA filters on providerType).
        var catalog = mock(IdentityProviderCatalog.class);
        var providers = mock(LoginProviderService.class);
        when(catalog.listRegistrations())
                .thenReturn(List.of(registration("p", "https://wiki.example.com/oauth/authorize")));
        when(providers.listEnabled()).thenReturn(List.of(provider("p", LoginProvider.ProviderType.OUTLINE)));

        var views = new IdentityProviderDiscoveryController(catalog, providers, mock(DevLoginService.class))
                .list()
                .getBody();

        assertThat(views)
                .extracting(IdentityProviderViewDTO::registrationId, IdentityProviderViewDTO::providerType)
                .containsExactly(tuple("p", "OUTLINE"));
    }

    @Test
    @DisplayName("a registration without a configured provider is not listed")
    void registrationWithoutProviderIsNotListed() {
        var catalog = mock(IdentityProviderCatalog.class);
        when(catalog.listRegistrations())
                .thenReturn(List.of(registration("p", "https://github.com/login/oauth/authorize")));

        var views = new IdentityProviderDiscoveryController(
                        catalog, mock(LoginProviderService.class), mock(DevLoginService.class))
                .list()
                .getBody();

        assertThat(views).isEmpty();
    }

    private static LoginProvider provider(String registrationId, LoginProvider.ProviderType type) {
        var provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        return provider;
    }

    @Test
    @DisplayName("a host-less (opaque) authorization URI has an empty baseUrl")
    void hostlessUriHasEmptyBaseUrl() {
        // An opaque URI parses but has a null host, without depending on the builder accepting a null
        // authorizationUri.
        ClientRegistration reg = registration("urn:example:authorize");
        assertThat(IdentityProviderDiscoveryController.baseUrlOf(reg)).isEmpty();
    }
}
