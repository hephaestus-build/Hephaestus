package de.tum.cit.aet.hephaestus.core.auth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.OidcIssuerPolicy;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * nOAuth linchpin: every login {@link ClientRegistration} built from a {@code login_provider} row MUST
 * key its principal on an IdP-stable, immutable subject ({@code id}/{@code sub}) — never a mutable
 * handle (a renameable GitLab {@code username}, or an {@code email} an attacker can set on a controlled
 * IdP). {@code IdentityLink.subject} is keyed on this; a regression is the classic nOAuth takeover.
 * Also checks the GitHub vs GitLab endpoint wiring.
 */
class LoginProviderClientRegistrationRepositoryTest extends BaseUnitTest {

    private static final Set<String> STABLE_SUBJECT_ATTRIBUTES = Set.of("id", "sub");
    private static final OutlineOriginPolicy OUTLINE_ORIGINS =
            new OutlineOriginPolicy(Set.of("https://wiki.example.com"));

    private static LoginProvider provider(
            String registrationId, LoginProvider.ProviderType type, String baseUrl, String scopes) {
        LoginProvider p = new LoginProvider();
        p.setRegistrationId(registrationId);
        p.setType(type);
        p.setDisplayName(registrationId);
        p.setBaseUrl(baseUrl);
        p.setClientId("client-id");
        p.setClientSecret("client-secret");
        p.setScopes(scopes);
        p.setEnabled(true);
        return p;
    }

    @Test
    void everyRegistration_usesStableSubjectAttribute() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByEnabledTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(
                        provider(
                                "github",
                                LoginProvider.ProviderType.GITHUB,
                                "https://github.com",
                                "read:user user:email"),
                        provider(
                                "gitlab-lrz",
                                LoginProvider.ProviderType.GITLAB,
                                "https://gitlab.lrz.de",
                                "openid profile")));

        List<ClientRegistration> registrations = new LoginProviderClientRegistrationRepository(
                        repo, "", OUTLINE_ORIGINS, new OidcIssuerPolicy(Set.of()), mock(OidcProviderDiscovery.class))
                .listRegistrations();

        assertThat(registrations).hasSize(2);
        for (ClientRegistration registration : registrations) {
            assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                    .as(
                            "'%s' must key the principal on an IdP-stable subject (nOAuth defence)",
                            registration.getRegistrationId())
                    .isIn(STABLE_SUBJECT_ATTRIBUTES);
        }
    }

    @Test
    void slackIsAnOidcProviderKeyedOnSubAndDiscoverableForAccountLinking() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByEnabledTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(
                        provider(
                                "github",
                                LoginProvider.ProviderType.GITHUB,
                                "https://github.com",
                                "read:user user:email"),
                        provider(
                                "slack",
                                LoginProvider.ProviderType.SLACK,
                                "https://slack.com",
                                "openid profile email")));
        when(repo.findByRegistrationId("slack"))
                .thenReturn(Optional.of(provider(
                        "slack", LoginProvider.ProviderType.SLACK, "https://slack.com", "openid profile email")));

        LoginProviderClientRegistrationRepository repository = new LoginProviderClientRegistrationRepository(
                repo, "", OUTLINE_ORIGINS, new OidcIssuerPolicy(Set.of()), mock(OidcProviderDiscovery.class));

        List<ClientRegistration> picker = repository.listRegistrations();
        assertThat(picker).extracting(ClientRegistration::getRegistrationId).containsExactly("github", "slack");

        ClientRegistration slack = repository.findByRegistrationId("slack");
        assertNotNull(slack);
        assertThat(slack.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://slack.com/openid/connect/authorize");
        assertThat(slack.getProviderDetails().getTokenUri()).isEqualTo("https://slack.com/api/openid.connect.token");
        assertThat(slack.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://slack.com/api/openid.connect.userInfo");
        assertThat(slack.getProviderDetails().getJwkSetUri()).isEqualTo("https://slack.com/openid/connect/keys");
        assertThat(slack.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                .isEqualTo("sub");
    }

    @Test
    void primaryProviderSatisfiesSignInReadiness() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByEnabledTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(
                        provider("github", LoginProvider.ProviderType.GITHUB, "https://github.com", "read:user")));

        assertThat(new LoginProviderClientRegistrationRepository(
                                repo,
                                "",
                                OUTLINE_ORIGINS,
                                new OidcIssuerPolicy(Set.of()),
                                mock(OidcProviderDiscovery.class))
                        .hasEnabledPrimarySignInProvider())
                .isTrue();
    }

    @Test
    void signInReadinessQueriesOnlyPrimaryProviderTypes() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        assertThat(new LoginProviderClientRegistrationRepository(
                                repo,
                                "",
                                OUTLINE_ORIGINS,
                                new OidcIssuerPolicy(Set.of()),
                                mock(OidcProviderDiscovery.class))
                        .hasEnabledPrimarySignInProvider())
                .isFalse();
        verify(repo).findByEnabledTrueOrderByDisplayNameAsc();
    }

    @Test
    void outlineIsPlainOauth2KeyedOnIdWithAuthInfoUserinfo() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByRegistrationId("outline"))
                .thenReturn(Optional.of(
                        provider("outline", LoginProvider.ProviderType.OUTLINE, "https://wiki.example.com", "read")));

        ClientRegistration outline = new LoginProviderClientRegistrationRepository(
                        repo, "", OUTLINE_ORIGINS, new OidcIssuerPolicy(Set.of()), mock(OidcProviderDiscovery.class))
                .findByRegistrationId("outline");

        assertNotNull(outline);
        assertThat(outline.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://wiki.example.com/oauth/authorize");
        assertThat(outline.getProviderDetails().getTokenUri()).isEqualTo("https://wiki.example.com/oauth/token");
        assertThat(outline.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://wiki.example.com/api/auth.info");
        assertThat(outline.getProviderDetails().getJwkSetUri()).isNull();
        assertThat(outline.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                .isEqualTo("id");
    }

    @Test
    void outlineRegistrationDisappearsAfterOriginApprovalIsRemoved() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByRegistrationId("outline"))
                .thenReturn(Optional.of(
                        provider("outline", LoginProvider.ProviderType.OUTLINE, "https://wiki.example.com", "read")));

        ClientRegistration outline = new LoginProviderClientRegistrationRepository(
                        repo,
                        "",
                        new OutlineOriginPolicy(Set.of()),
                        new OidcIssuerPolicy(Set.of()),
                        mock(OidcProviderDiscovery.class))
                .findByRegistrationId("outline");

        assertThat(outline).isNull();
    }

    @Test
    void gitlabEndpointsHangOffTheInstanceBaseUrl() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByRegistrationId("gitlab-lrz"))
                .thenReturn(Optional.of(
                        provider("gitlab-lrz", LoginProvider.ProviderType.GITLAB, "https://gitlab.lrz.de", "openid")));

        ClientRegistration reg = new LoginProviderClientRegistrationRepository(
                        repo, "", OUTLINE_ORIGINS, new OidcIssuerPolicy(Set.of()), mock(OidcProviderDiscovery.class))
                .findByRegistrationId("gitlab-lrz");

        assertNotNull(reg);
        assertThat(reg.getProviderDetails().getAuthorizationUri()).isEqualTo("https://gitlab.lrz.de/oauth/authorize");
        assertThat(reg.getProviderDetails().getTokenUri()).isEqualTo("https://gitlab.lrz.de/oauth/token");
        assertThat(reg.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://gitlab.lrz.de/api/v4/user");
    }

    @Test
    void redirectUri_carriesTheConfiguredApiBasePath() {
        LoginProviderRepository repo = mock(LoginProviderRepository.class);
        when(repo.findByRegistrationId("github"))
                .thenReturn(Optional.of(
                        provider("github", LoginProvider.ProviderType.GITHUB, "https://github.com", "read:user")));

        ClientRegistration prefixed = new LoginProviderClientRegistrationRepository(
                        repo,
                        "/api",
                        OUTLINE_ORIGINS,
                        new OidcIssuerPolicy(Set.of()),
                        mock(OidcProviderDiscovery.class))
                .findByRegistrationId("github");
        assertNotNull(prefixed);
        assertThat(prefixed.getRedirectUri()).isEqualTo("{baseUrl}/api/login/oauth2/code/{registrationId}");

        ClientRegistration root = new LoginProviderClientRegistrationRepository(
                        repo, "", OUTLINE_ORIGINS, new OidcIssuerPolicy(Set.of()), mock(OidcProviderDiscovery.class))
                .findByRegistrationId("github");
        assertNotNull(root);
        assertThat(root.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    }
}
