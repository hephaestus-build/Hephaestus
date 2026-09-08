package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.dev.DevLoginService;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IdentityProviderDiscoveryControllerTest extends BaseUnitTest {

    private final LoginProviderService providers = mock(LoginProviderService.class);
    private final DevLoginService devLogin = mock(DevLoginService.class);
    private final IdentityProviderDiscoveryController controller =
            new IdentityProviderDiscoveryController(providers, devLogin);

    @ParameterizedTest
    @EnumSource(LoginProvider.ProviderType.class)
    void shouldExposeConfiguredTypeAndUrlWhenListingSignInAndLinkingOptions(LoginProvider.ProviderType type) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId("organization");
        provider.setType(type);
        provider.setBaseUrl("https://identity.example.com:8443/realms/team/");
        provider.setDisplayName("Organization account");
        when(providers.listEnabled()).thenReturn(List.of(provider));

        assertThat(controller.list().getBody())
                .containsExactly(new IdentityProviderDiscoveryController.IdentityProviderViewDTO(
                        "organization", "Organization account", type.name(), provider.getBaseUrl()));
    }

    @Test
    void shouldKeepExactIssuerWhenListingOrganizationalSignIn() {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId("organization");
        provider.setType(LoginProvider.ProviderType.OIDC);
        provider.setBaseUrl("https://identity.example.com/realms/team/");
        provider.setDisplayName("Organization account");
        when(providers.listEnabled()).thenReturn(List.of(provider));

        assertThat(controller.list().getBody())
                .containsExactly(new IdentityProviderDiscoveryController.IdentityProviderViewDTO(
                        "organization", "Organization account", "OIDC", "https://identity.example.com/realms/team/"));
    }

    @Test
    void shouldOfferDevSignInOnlyWhenEnabled() {
        when(providers.listEnabled()).thenReturn(List.of());
        assertThat(controller.list().getBody()).isEmpty();

        when(devLogin.isEnabled()).thenReturn(true);
        assertThat(controller.list().getBody())
                .containsExactly(new IdentityProviderDiscoveryController.IdentityProviderViewDTO(
                        "dev", "Dev sign-in", "DEV", ""));
    }
}
