package de.tum.cit.aet.hephaestus.core.auth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the env → seed contract: a configured login provider is seeded once when absent, never
 * re-seeded when present (so an admin's later edits aren't clobbered), and unconfigured providers
 * are skipped. Providers are keyed by a stable, operator-chosen {@code registrationId} (e.g.
 * {@code gitlab-lrz}) and several seed in one boot.
 */
class LoginProviderServiceTest extends BaseUnitTest {

    private final LoginProviderRepository repository = mock(LoginProviderRepository.class);
    private final LoginProviderClientRegistrationRepository registrationCache =
            mock(LoginProviderClientRegistrationRepository.class);

    /** The real logger over a mocked writer: a mocked logger would assert nothing about what reaches the ledger. */
    private final AuthEventWriter authEventWriter = mock(AuthEventWriter.class);

    private final AuthEventLogger authEventLogger = new AuthEventLogger(authEventWriter);

    private LoginProviderService service(Map<String, AuthProperties.LoginProviderSeed> providers) {
        return new LoginProviderService(
                repository,
                registrationCache,
                AuthPropertiesFixture.withLoginProviders(providers),
                authEventLogger,
                new ObjectMapper(),
                new OutlineOriginPolicy(Set.of("https://wiki.example.com", "https://wiki.acme.test")));
    }

    private LoginProviderService adminService() {
        return service(Map.of());
    }

    private static AuthProperties.LoginProviderSeed githubSeed(String clientId, String secret) {
        return new AuthProperties.LoginProviderSeed(LoginProvider.ProviderType.GITHUB, "", clientId, secret, "");
    }

    private static AuthProperties.LoginProviderSeed gitlabSeed(
            String clientId, String secret, String baseUrl, String displayName) {
        return new AuthProperties.LoginProviderSeed(
                LoginProvider.ProviderType.GITLAB, baseUrl, clientId, secret, displayName);
    }

    private static AuthProperties.LoginProviderSeed outlineSeed(String clientId, String secret, String baseUrl) {
        return new AuthProperties.LoginProviderSeed(
                LoginProvider.ProviderType.OUTLINE, baseUrl, clientId, secret, "Team Wiki");
    }

    private static LoginProviderService.Draft gitlabDraft(
            String registrationId, String baseUrl, @Nullable String scopes) {
        return new LoginProviderService.Draft(
                registrationId,
                LoginProvider.ProviderType.GITLAB,
                "Self-hosted GitLab",
                baseUrl,
                "client-id",
                "client-secret",
                scopes);
    }

    private static LoginProviderService.Draft outlineDraft(
            String registrationId, String baseUrl, @Nullable String scopes) {
        return new LoginProviderService.Draft(
                registrationId,
                LoginProvider.ProviderType.OUTLINE,
                "Team Wiki",
                baseUrl,
                "client-id",
                "client-secret",
                scopes);
    }

    @Test
    void seedsConfiguredGithubWhenAbsent() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of("github", githubSeed("client-id", "secret"))).seedFromEnvOnStartup();

        ArgumentCaptor<LoginProvider> captor = ArgumentCaptor.forClass(LoginProvider.class);
        verify(repository).save(captor.capture());
        LoginProvider seeded = captor.getValue();
        assertThat(seeded.getRegistrationId()).isEqualTo("github");
        assertThat(seeded.getType()).isEqualTo(LoginProvider.ProviderType.GITHUB);
        assertThat(seeded.getBaseUrl()).isEqualTo("https://github.com");
        assertThat(seeded.getScopes()).isEqualTo("read:user user:email"); // defaulted by type
        assertThat(seeded.isSeededFromEnv()).isTrue();
        assertThat(seeded.isEnabled()).isTrue();
    }

    @Test
    void seedsSelfHostedGitlabUnderItsDescriptiveRegistrationId() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);
        // The whole point of the map: a self-hosted GitLab keeps a descriptive, stable id (gitlab-lrz),
        // not a forced generic "gitlab" — so several GitLab instances can coexist on one Hephaestus.

        service(Map.of("gitlab-lrz", gitlabSeed("client-id", "secret", "https://gitlab.lrz.de", "GitLab LRZ")))
                .seedFromEnvOnStartup();

        ArgumentCaptor<LoginProvider> captor = ArgumentCaptor.forClass(LoginProvider.class);
        verify(repository).save(captor.capture());
        LoginProvider seeded = captor.getValue();
        assertThat(seeded.getRegistrationId()).isEqualTo("gitlab-lrz");
        assertThat(seeded.getType()).isEqualTo(LoginProvider.ProviderType.GITLAB);
        assertThat(seeded.getBaseUrl()).isEqualTo("https://gitlab.lrz.de");
        assertThat(seeded.getDisplayName()).isEqualTo("GitLab LRZ");
        assertThat(seeded.getScopes()).isEqualTo("read_user"); // OAuth2 (no openid), defaulted by type
        assertThat(seeded.isSeededFromEnv()).isTrue();
        assertThat(seeded.isEnabled()).isTrue();
    }

    @Test
    void seedsMultipleProvidersInOneBoot() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of(
                        "github",
                        githubSeed("gh-id", "gh-secret"),
                        "gitlab-lrz",
                        gitlabSeed("gl-id", "gl-secret", "https://gitlab.lrz.de", "GitLab LRZ")))
                .seedFromEnvOnStartup();

        ArgumentCaptor<LoginProvider> captor = ArgumentCaptor.forClass(LoginProvider.class);
        verify(repository, Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(LoginProvider::getRegistrationId)
                .containsExactlyInAnyOrder("github", "gitlab-lrz");
    }

    @Test
    void skipsSeedWhenAnInstanceWithSameTypeAndBaseUrlAlreadyExists() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);
        // A row for this (type, base_url) already exists (e.g. an admin added it, or a prior id) — the
        // uniqueness guard must skip rather than crash startup on a constraint violation.
        when(repository.existsByTypeAndBaseUrl(LoginProvider.ProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(true);

        service(Map.of("gitlab-lrz", gitlabSeed("client-id", "secret", "https://gitlab.lrz.de", "GitLab LRZ")))
                .seedFromEnvOnStartup();

        verify(repository, never()).save(any());
    }

    @Test
    void skipsSeedOfInvalidRegistrationId() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of("Bad Id!", githubSeed("client-id", "secret"))).seedFromEnvOnStartup();

        verify(repository, never()).save(any());
    }

    @Test
    void doesNotReseedWhenAlreadyPresent() {
        when(repository.existsByRegistrationId(any())).thenReturn(true);

        service(Map.of("github", githubSeed("client-id", "secret"))).seedFromEnvOnStartup();

        verify(repository, never()).save(any());
    }

    @Test
    void skipsUnconfiguredProviders() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of("github", githubSeed("", ""), "gitlab", gitlabSeed("", "", "https://gitlab.com", "GitLab")))
                .seedFromEnvOnStartup();

        verify(repository, never()).save(any());
    }

    /**
     * The shipped {@code outline} seed slot is blank on every deployment that does not run a wiki:
     * both {@code OUTLINE_OAUTH_CLIENT_ID} and {@code OUTLINE_OAUTH_BASE_URL} default to empty. The
     * unconfigured gate must fire FIRST — before the id check, the existence probe and, decisively,
     * before {@code resolveBaseUrl}, which 422s on a blank base URL. Asserted structurally: the entry
     * touches the repository not at all.
     */
    @Test
    void skipsTheBlankOutlineSeedSlotBeforeItCanValidateTheBlankBaseUrl() {
        service(Map.of("outline", outlineSeed("", "", ""))).seedFromEnvOnStartup();

        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void seedsOutlineFromItsBaseUrlWithTheReadScope() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of("outline", outlineSeed("client-id", "secret", "https://wiki.acme.test/")))
                .seedFromEnvOnStartup();

        ArgumentCaptor<LoginProvider> captor = ArgumentCaptor.forClass(LoginProvider.class);
        verify(repository).save(captor.capture());
        LoginProvider seeded = captor.getValue();
        assertThat(seeded.getRegistrationId()).isEqualTo("outline");
        assertThat(seeded.getType()).isEqualTo(LoginProvider.ProviderType.OUTLINE);
        assertThat(seeded.getBaseUrl()).isEqualTo("https://wiki.acme.test");
        assertThat(seeded.getScopes()).isEqualTo("read"); // pinned by type — never 'openid' (Outline is not OIDC)
        assertThat(seeded.isSeededFromEnv()).isTrue();
    }

    /**
     * Half-configured (a client id but no base URL): {@code resolveBaseUrl} 422s, and the seeder must
     * absorb that into a skip. A credentialed-but-URL-less Outline slot cannot crash startup.
     */
    @Test
    void skipsOutlineSeedWithAClientIdButNoBaseUrl() {
        when(repository.existsByRegistrationId(any())).thenReturn(false);

        service(Map.of("outline", outlineSeed("client-id", "secret", ""))).seedFromEnvOnStartup();

        verify(repository, never()).save(any());
    }

    /**
     * A half-configured slot (client id exported, secret forgotten) must NOT seed. Seeding it would offer an
     * ENABLED provider whose every OAuth exchange 401s at the token endpoint with an opaque IdP error. This is
     * a per-slot property of the seed, so it holds for all four provider types — not just Outline.
     */
    @ParameterizedTest
    @EnumSource(LoginProvider.ProviderType.class)
    void skipsSeedWithAClientIdButNoClientSecret(LoginProvider.ProviderType type) {
        service(Map.of(
                        "provider",
                        new AuthProperties.LoginProviderSeed(type, "https://host.example.com", "client-id", "", "")))
                .seedFromEnvOnStartup();

        Mockito.verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(LoginProvider.ProviderType.class)
    void skipsSeedWithAClientSecretButNoClientId(LoginProvider.ProviderType type) {
        service(Map.of(
                        "provider",
                        new AuthProperties.LoginProviderSeed(type, "https://host.example.com", "  ", "secret", "")))
                .seedFromEnvOnStartup();

        Mockito.verifyNoInteractions(repository);
    }

    @Test
    void createSelfHostedGitlabDefaultsScopesAndEvictsCache() {
        when(repository.existsByRegistrationId("gitlab-acme")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginProvider created = adminService().create(gitlabDraft("gitlab-acme", "https://gitlab.acme.test/", null));

        assertThat(created.getType()).isEqualTo(LoginProvider.ProviderType.GITLAB);
        assertThat(created.getBaseUrl()).isEqualTo("https://gitlab.acme.test"); // trailing slash stripped
        assertThat(created.getScopes()).isEqualTo("read_user"); // defaulted by type (OAuth2, no openid)
        assertThat(created.isSeededFromEnv()).isFalse();
        verify(registrationCache).evict("gitlab-acme");
    }

    @Test
    void createRejectsDuplicateRegistrationId() {
        when(repository.existsByRegistrationId("gitlab-acme")).thenReturn(true);

        assertThatThrownBy(() -> adminService().create(gitlabDraft("gitlab-acme", "https://gitlab.acme.test", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsGitlabOpenidScope() {
        // openid flips Spring to the OIDC path and 500s the callback (no jwkSetUri) — must be rejected.
        assertThatThrownBy(() -> adminService()
                        .create(gitlabDraft("gitlab-x", "https://gitlab.acme.test", "openid profile read_user")))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsGitlabOpenidScope() {
        LoginProvider existing = gitlabProvider("gitlab", "sealed");
        when(repository.findByRegistrationId("gitlab")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> adminService()
                        .update(
                                "gitlab",
                                new LoginProviderService.Patch(null, null, null, null, "openid read_user", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createOutlineDefaultsToReadScopeAndValidatesBaseUrl() {
        // OUTLINE mirrors the GITLAB self-hosted shape: per-instance base URL (validated), plain OAuth2
        // with a pinned minimal "read" scope — see LoginProviderService.OUTLINE_SCOPES.
        when(repository.existsByRegistrationId("outline-acme")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginProvider created = adminService().create(outlineDraft("outline-acme", "https://wiki.acme.test/", null));

        assertThat(created.getType()).isEqualTo(LoginProvider.ProviderType.OUTLINE);
        assertThat(created.getBaseUrl()).isEqualTo("https://wiki.acme.test"); // trailing slash stripped
        assertThat(created.getScopes()).isEqualTo("read"); // defaulted by type (plain OAuth2, no openid)
        verify(registrationCache).evict("outline-acme");
    }

    @Test
    void createRejectsOutlineOpenidScope() {
        // Outline is NOT an OIDC provider: openid flips Spring to the OIDC path and 500s the callback
        // (no id_token, no jwkSetUri) — same failure mode the GitLab guard closes.
        assertThatThrownBy(
                        () -> adminService().create(outlineDraft("outline-x", "https://wiki.acme.test", "openid read")))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateRejectsOutlineOpenidScope() {
        LoginProvider existing = outlineProvider("outline", "sealed");
        when(repository.findByRegistrationId("outline")).thenReturn(java.util.Optional.of(existing));

        assertThatThrownBy(() -> adminService()
                        .update("outline", new LoginProviderService.Patch(null, null, null, null, "openid read", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsNonHttpsOutlineBaseUrl() {
        // SSRF / HTTPS guard: the Outline base URL takes the same ServerUrlValidator posture as GitLab.
        assertThatThrownBy(() -> adminService().create(outlineDraft("outline-x", "http://wiki.acme.test", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsUnapprovedOutlineOrigin() {
        assertThatThrownBy(() -> adminService().create(outlineDraft("outline-x", "https://wiki.denied.test", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsMalformedRegistrationId() {
        assertThatThrownBy(() -> adminService().create(gitlabDraft("Bad Id!", "https://gitlab.acme.test", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void disableRefusesWhenItIsTheLastEnabledProvider() {
        LoginProvider only = new LoginProvider();
        only.setRegistrationId("github");
        only.setType(LoginProvider.ProviderType.GITHUB);
        only.setEnabled(true);
        when(repository.findByRegistrationId("github")).thenReturn(Optional.of(only));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(only));

        assertThatThrownBy(() -> adminService()
                        .update("github", new LoginProviderService.Patch(null, null, null, null, null, false)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void deleteRefusesWhenItIsTheLastEnabledProvider() {
        LoginProvider only = gitlabProvider("gitlab", "sealed");
        when(repository.findByRegistrationId("gitlab")).thenReturn(Optional.of(only));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(only));

        assertThatThrownBy(() -> adminService().delete("gitlab")).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).delete(any());
        verify(registrationCache, never()).evict(any());
    }

    /**
     * The lockout guard counts SIGN-IN methods. Slack and Outline are link-only ({@code isLinkOnly()}): they
     * are filtered off the login picker and rejected at flow begin, so they can never sign anyone in. An
     * instance whose only provider is Outline must still be able to delete it.
     */
    @Test
    void deleteAllowsTheLastEnabledProviderWhenItIsLinkOnly() {
        LoginProvider outline = outlineProvider("outline", "sealed");
        when(repository.findByRegistrationId("outline")).thenReturn(Optional.of(outline));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(outline));

        adminService().delete("outline");

        verify(repository).delete(outline);
        verify(registrationCache).evict("outline");
    }

    @Test
    void disableAllowsTheLastEnabledProviderWhenItIsLinkOnly() {
        LoginProvider outline = outlineProvider("outline", "sealed");
        when(repository.findByRegistrationId("outline")).thenReturn(Optional.of(outline));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(outline));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        LoginProvider saved =
                adminService().update("outline", new LoginProviderService.Patch(null, null, null, null, null, false));

        assertThat(saved.isEnabled()).isFalse();
    }

    /**
     * The mirror image: a link-only provider must not PROP UP the count either. With GitHub + Outline enabled,
     * deleting GitHub leaves nobody able to sign in — the old count saw a non-empty list and waved it through.
     */
    @Test
    void deleteRefusesTheLastSignInProviderEvenWhenALinkOnlyProviderRemains() {
        LoginProvider github = new LoginProvider();
        github.setRegistrationId("github");
        github.setType(LoginProvider.ProviderType.GITHUB);
        github.setEnabled(true);
        LoginProvider outline = outlineProvider("outline", "sealed");
        when(repository.findByRegistrationId("github")).thenReturn(Optional.of(github));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(github, outline));

        assertThatThrownBy(() -> adminService().delete("github")).isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void createRejectsNonHttpsGitlabBaseUrl() {
        // SSRF / HTTPS guard: ServerUrlValidator rejects http:// (and loopback/internal) base URLs.
        assertThatThrownBy(() -> adminService().create(gitlabDraft("gitlab-x", "http://gitlab.acme.test", null)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateLeavesSealedSecretUnchangedWhenPatchSecretIsNullOrBlank() {
        LoginProvider existing = gitlabProvider("gitlab", "sealed-secret");
        when(repository.findByRegistrationId("gitlab")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService().update("gitlab", new LoginProviderService.Patch("Renamed", null, null, null, null, null));
        assertThat(existing.getClientSecret()).isEqualTo("sealed-secret"); // null secret → unchanged

        adminService().update("gitlab", new LoginProviderService.Patch(null, null, null, "   ", null, null));
        assertThat(existing.getClientSecret()).isEqualTo("sealed-secret"); // blank secret → unchanged
    }

    @Test
    void updateReplacesSecretWhenPatchSecretIsPresent() {
        LoginProvider existing = gitlabProvider("gitlab", "old-secret");
        when(repository.findByRegistrationId("gitlab")).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService().update("gitlab", new LoginProviderService.Patch(null, null, null, "new-secret", null, null));
        assertThat(existing.getClientSecret()).isEqualTo("new-secret");
    }

    private static LoginProvider outlineProvider(String registrationId, String clientSecret) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.OUTLINE);
        provider.setDisplayName("Team Wiki");
        provider.setBaseUrl("https://wiki.example.com");
        provider.setClientId("client-id");
        provider.setClientSecret(clientSecret);
        provider.setScopes("read");
        provider.setEnabled(true);
        return provider;
    }

    @Test
    void adminMutationsLandOnTheAuthEventTrail() {
        when(repository.existsByRegistrationId("gitlab-acme")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        LoginProvider existing = gitlabProvider("gitlab-acme", "sealed");
        when(repository.findByRegistrationId("gitlab-acme")).thenReturn(Optional.of(existing));
        // Two enabled providers, so neither disable nor delete trips the last-provider lockout guard.
        when(repository.findByEnabledTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(existing, gitlabProvider("gitlab-other", "sealed")));
        LoginProviderService service = adminService();

        service.create(gitlabDraft("gitlab-acme", "https://gitlab.acme.test", null));
        service.update("gitlab-acme", new LoginProviderService.Patch(null, null, null, "rotated", null, false));
        service.delete("gitlab-acme");

        ArgumentCaptor<AuthEventData> captor = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter, Mockito.times(3)).write(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuthEventData::type)
                .containsExactly(
                        AuthEvent.EventType.LOGIN_PROVIDER_CREATED,
                        AuthEvent.EventType.LOGIN_PROVIDER_UPDATED,
                        AuthEvent.EventType.LOGIN_PROVIDER_DELETED);
        assertThat(captor.getAllValues())
                .extracting(AuthEventData::details)
                .allSatisfy(details -> assertThat(details).contains("\"registrationId\":\"gitlab-acme\""));
    }

    @Test
    void updateAuditNamesTheRotatedSecretWithoutRecordingIt() {
        LoginProvider existing = gitlabProvider("gitlab-acme", "old-secret");
        when(repository.findByRegistrationId("gitlab-acme")).thenReturn(Optional.of(existing));
        when(repository.findByEnabledTrueOrderByDisplayNameAsc()).thenReturn(List.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        adminService()
                .update(
                        "gitlab-acme",
                        new LoginProviderService.Patch("Renamed", null, null, "super-secret-value", null, null));

        ArgumentCaptor<AuthEventData> captor = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(captor.capture());
        String details = captor.getValue().details();
        assertThat(details).contains("\"changed\":[\"displayName\",\"clientSecret\"]");
        assertThat(details).doesNotContain("super-secret-value").doesNotContain("old-secret");
    }

    private static LoginProvider gitlabProvider(String registrationId, String clientSecret) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.GITLAB);
        provider.setDisplayName("GitLab");
        provider.setBaseUrl("https://gitlab.example.com");
        provider.setClientId("client-id");
        provider.setClientSecret(clientSecret);
        provider.setScopes("read_user");
        provider.setEnabled(true);
        return provider;
    }
}
