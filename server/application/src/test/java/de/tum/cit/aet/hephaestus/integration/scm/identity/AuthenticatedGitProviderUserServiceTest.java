package de.tum.cit.aet.hephaestus.integration.scm.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class AuthenticatedGitProviderUserServiceTest extends BaseUnitTest {
    @Mock
    private UserRepository users;

    @Mock
    private IdentityProviderRepository providers;

    @Mock
    private AccountIdentityQuery identities;

    @InjectMocks
    private AuthenticatedGitProviderUserService service;

    @BeforeEach
    void authenticate() {
        var jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject("42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldResolveExactCurrentMirrorProfile() {
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example");
        provider.setId(7L);
        when(identities.activeLinksForAccount(42L))
                .thenReturn(List.of(new AccountIdentityQuery.IdentityView(10L, 7L, "90001", "")));
        when(providers.findById(7L)).thenReturn(Optional.of(provider));
        var user = new User();
        user.setId(99L);
        when(users.findByNativeIdAndProviderId(90001L, 7L)).thenReturn(Optional.of(user));
        assertThat(service.resolveCurrentUser()).containsSame(user);
    }

    @Test
    void shouldRefuseMissingMirrorWithoutInventingProfileFacts() {
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example");
        provider.setId(7L);
        when(identities.activeLinksForAccount(42L))
                .thenReturn(List.of(new AccountIdentityQuery.IdentityView(10L, 7L, "90001", "")));
        when(providers.findById(7L)).thenReturn(Optional.of(provider));
        assertThatThrownBy(service::resolveCurrentUser)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("provider profile is not available");
    }

    @Test
    void shouldRejectAnotherGitLabInstance() {
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://other.example");
        provider.setId(7L);
        when(identities.activeLinksForAccount(42L))
                .thenReturn(List.of(new AccountIdentityQuery.IdentityView(10L, 7L, "90001", "")));
        when(providers.findById(7L)).thenReturn(Optional.of(provider));
        assertThatThrownBy(() -> service.resolveCurrentGitLabUser("https://gitlab.example"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Connect your GitLab account");
    }

    @Test
    void shouldReturnEmptyForAnUnlinkedAccount() {
        assertThat(service.resolveCurrentUser()).isEmpty();
    }
}
