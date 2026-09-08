package de.tum.cit.aet.hephaestus.integration.core.connection.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery.IdentityLinkView;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class AuthenticatedGitProviderUserServiceTest extends BaseUnitTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long GITLAB_PROVIDER_ID = 7L;
    private static final long GITHUB_PROVIDER_ID = 9L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private IdentityProviderRepository gitProviderRepository;

    @Mock
    private AccountIdentityQuery accountIdentityQuery;

    @InjectMocks
    private AuthenticatedGitProviderUserService service;

    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "ES256")
                .subject(String.valueOf(ACCOUNT_ID))
                .claim("preferred_username", "gitlabuser")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolveOrProvision_provisionsGitLabUserFromIdentityLink_whenNoClaimPresent() {
        IdentityLinkView gitlab = view(100L, GITLAB_PROVIDER_ID, "18024", "gitlabuser");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(gitlab));
        IdentityProvider provider =
                gitProvider(GITLAB_PROVIDER_ID, IdentityProviderType.GITLAB, "https://gitlab.lrz.de");
        when(gitProviderRepository.findById(GITLAB_PROVIDER_ID)).thenReturn(Optional.of(provider));

        User provisioned = user(555L, "gitlabuser", 18024L, provider);
        when(userRepository.findByNativeIdAndProviderId(18024L, GITLAB_PROVIDER_ID))
                .thenReturn(Optional.empty(), Optional.of(provisioned));
        when(userRepository.findById(555L)).thenReturn(Optional.of(provisioned));

        Optional<User> result = service.resolveOrProvisionCurrentUser();

        assertThat(result).containsSame(provisioned);

        verify(userRepository)
                .upsertUser(
                        eq(18024L),
                        eq(GITLAB_PROVIDER_ID),
                        eq("gitlabuser"),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("USER"),
                        any(),
                        any(),
                        any());

        verify(accountIdentityQuery).linkExternalActor(100L, 555L);
    }

    @Test
    void shouldResolveFirstLinkedScmActorWithoutOverwritingSyncedProfile() {
        IdentityLinkView github = view(200L, GITHUB_PROVIDER_ID, "999", "former-login");
        IdentityLinkView gitlab = view(300L, GITLAB_PROVIDER_ID, "18024", "gitlabuser");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(github, gitlab));
        IdentityProvider provider = gitProvider(GITHUB_PROVIDER_ID, IdentityProviderType.GITHUB, "https://github.com");
        when(gitProviderRepository.findById(GITHUB_PROVIDER_ID)).thenReturn(Optional.of(provider));
        User existing = user(555L, "renamed-login", 999L, provider);
        when(userRepository.findByNativeIdAndProviderId(999L, GITHUB_PROVIDER_ID))
                .thenReturn(Optional.of(existing));

        assertThat(service.resolveOrProvisionCurrentUser()).containsSame(existing);
        assertThat(existing.getLogin()).isEqualTo("renamed-login");
        verify(userRepository, never()).getCurrentUser();
        verify(userRepository, never()).acquireLoginLock(anyString(), anyLong());
    }

    @Test
    void ensureGitLab_succeeds_forGitLabLoginOnlyUser() {
        IdentityLinkView gitlab = view(100L, GITLAB_PROVIDER_ID, "18024", "gitlabuser");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(gitlab));
        IdentityProvider gl = gitProvider(GITLAB_PROVIDER_ID, IdentityProviderType.GITLAB, "https://gitlab.lrz.de");
        when(gitProviderRepository.findById(GITLAB_PROVIDER_ID)).thenReturn(Optional.of(gl));
        User provisioned = user(555L, "gitlabuser", 18024L, gl);
        when(userRepository.findByNativeIdAndProviderId(18024L, GITLAB_PROVIDER_ID))
                .thenReturn(Optional.empty(), Optional.of(provisioned));
        lenient().when(userRepository.findById(555L)).thenReturn(Optional.of(provisioned));

        service.ensureCurrentGitLabUserExists();

        verify(accountIdentityQuery).linkExternalActor(100L, 555L);
    }

    @Test
    void ensureGitLab_throwsConflict_forGitHubOnlyUser() {
        IdentityLinkView github = view(200L, GITHUB_PROVIDER_ID, "999", "ghuser");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(github));
        IdentityProvider gh = gitProvider(GITHUB_PROVIDER_ID, IdentityProviderType.GITHUB, "https://github.com");
        when(gitProviderRepository.findById(GITHUB_PROVIDER_ID)).thenReturn(Optional.of(gh));

        assertThatThrownBy(() -> service.ensureCurrentGitLabUserExists())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never())
                .upsertUser(
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void resolveOrProvision_nonNumericSubject_throwsConflict() {

        IdentityLinkView poisoned = view(100L, GITLAB_PROVIDER_ID, "attacker-handle", "attacker-handle");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(poisoned));
        IdentityProvider provider =
                gitProvider(GITLAB_PROVIDER_ID, IdentityProviderType.GITLAB, "https://gitlab.lrz.de");
        when(gitProviderRepository.findById(GITLAB_PROVIDER_ID)).thenReturn(Optional.of(provider));

        assertThatThrownBy(() -> service.resolveOrProvisionCurrentUser())
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never())
                .upsertUser(
                        anyLong(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any());
        verify(accountIdentityQuery, never()).linkExternalActor(anyLong(), anyLong());
    }

    @Test
    void resolveOrProvision_returnsEmpty_whenAccountHasNoLinks() {
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of());

        assertThat(service.resolveOrProvisionCurrentUser()).isEmpty();
    }

    private static IdentityLinkView view(long linkId, long providerId, String subject, String username) {
        return new IdentityLinkView(linkId, providerId, subject, username, "Display", null, null, null, null);
    }

    private static IdentityProvider gitProvider(long id, IdentityProviderType type, String serverUrl) {
        IdentityProvider p = new IdentityProvider(type, serverUrl);
        p.setId(id);
        return p;
    }

    private static User user(long id, String login, long nativeId, IdentityProvider provider) {
        User u = new User();
        u.setId(id);
        u.setNativeId(nativeId);
        u.setProvider(provider);
        u.setLogin(login);
        u.setAvatarUrl("");
        u.setHtmlUrl("");
        u.setType(User.Type.USER);
        return u;
    }
}
