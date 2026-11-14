package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class OutlineIdentityIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private SystemSecrets systemSecrets;

    private static final String OUTLINE_USER = "0aa1bb2c-user";

    private static final String OUTLINE_TEAM = "9ff8ee7d-team";

    @Autowired
    private AccountProvisioningService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Test
    void linkingOutlineAttachesExactlyOneOutlineIdentityToTheSameAccount() {
        seedProvider("github-outlinelink", LoginProvider.ProviderType.GITHUB);
        seedProvider("outline-link", LoginProvider.ProviderType.OUTLINE);

        Account github = service.resolveOrProvision(
                        "github-outlinelink",
                        "gh-2",
                        githubPrincipal("gh-2", "dev@example.com", "octocat"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        // While authenticated, they link their wiki identity (simulated auth.info principal).
        AccountProvisioningService.ProvisionResult linked = service.resolveOrProvision(
                "outline-link",
                OUTLINE_USER,
                outlinePrincipal(OUTLINE_USER, OUTLINE_TEAM, "dev@example.com"),
                AuthIntentCookie.Intent.link(persistedId(github.getId()), "/settings"));

        assertThat(linked.identityLinked()).isTrue();
        assertThat(linked.account().getId()).isEqualTo(persistedId(github.getId()));
        assertThat(accountRepository.findById(persistedId(github.getId()))).isPresent();

        List<Identity> links = identityRepository.findActiveByAccountId(persistedId(github.getId()));
        assertThat(links).extracting(Identity::getProviderUserKey).containsExactlyInAnyOrder("gh-2", OUTLINE_USER);
        Identity outline = links.stream()
                .filter(l -> l.getProviderUserKey().equals(OUTLINE_USER))
                .findFirst()
                .orElseThrow();
        assertThat(outline.getTeamKey()).isEqualTo(OUTLINE_TEAM);
        assertThat(outline.getLinkedVia()).isEqualTo(Identity.LinkedVia.MANUAL);
    }

    @Test
    void outlineLoginModeIsRefusedBecauseTheProviderIsLinkOnly() {
        seedProvider("outline-loginmode", LoginProvider.ProviderType.OUTLINE);

        // LOGIN mode on a link-only provider — the success handler maps this to
        // /auth/error?code=link_requires_auth.
        assertThatThrownBy(() -> service.resolveOrProvision(
                        "outline-loginmode",
                        OUTLINE_USER,
                        outlinePrincipal(OUTLINE_USER, OUTLINE_TEAM, "dev@example.com"),
                        AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(LinkOnlyProviderLoginException.class);

        assertThat(accountRepository.findByPrimaryEmail("dev@example.com")).isEmpty();
    }

    @Test
    void unauthenticatedOutlineLinkStartCreatesNoAccount() {
        seedProvider("outline-noauth", LoginProvider.ProviderType.OUTLINE);

        // A LINK-mode callback with no authenticated account binding must fail closed — never an
        // orphan.
        assertThatThrownBy(() -> service.resolveOrProvision(
                        "outline-noauth",
                        OUTLINE_USER,
                        outlinePrincipal(OUTLINE_USER, OUTLINE_TEAM, "dev@example.com"),
                        AuthIntentCookie.Intent.link(null, "/settings")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(accountRepository.findByPrimaryEmail("dev@example.com")).isEmpty();
    }

    @Test
    void relinkingAnOutlineIdentityOwnedByAnotherAccountIsRefused() {
        seedProvider("github-outlineconflict-a", LoginProvider.ProviderType.GITHUB);
        seedProvider("github-outlineconflict-b", LoginProvider.ProviderType.GITHUB);
        seedProvider("outline-conflict", LoginProvider.ProviderType.OUTLINE);

        Account owner = service.resolveOrProvision(
                        "github-outlineconflict-a",
                        "gh-owner",
                        githubPrincipal("gh-owner", "owner@example.com", "owner"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();
        service.resolveOrProvision(
                "outline-conflict",
                OUTLINE_USER,
                outlinePrincipal(OUTLINE_USER, OUTLINE_TEAM, "owner@example.com"),
                AuthIntentCookie.Intent.link(persistedId(owner.getId()), "/settings"));

        Account attacker = service.resolveOrProvision(
                        "github-outlineconflict-b",
                        "gh-attacker",
                        githubPrincipal("gh-attacker", "attacker@example.com", "attacker"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        assertThatThrownBy(() -> service.resolveOrProvision(
                        "outline-conflict",
                        OUTLINE_USER,
                        outlinePrincipal(OUTLINE_USER, OUTLINE_TEAM, "attacker@example.com"),
                        AuthIntentCookie.Intent.link(persistedId(attacker.getId()), "/settings")))
                .isInstanceOf(AccountLinkConflictException.class);

        assertThat(identityRepository.findActiveByAccountId(persistedId(attacker.getId())))
                .extracting(Identity::getProviderUserKey)
                .containsExactly("gh-attacker");
        assertThat(identityRepository.findActiveByAccountId(persistedId(owner.getId())))
                .extracting(Identity::getProviderUserKey)
                .contains(OUTLINE_USER);
    }

    private void seedProvider(String registrationId, LoginProvider.ProviderType type) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        provider.setDisplayName(registrationId);
        // A baseUrl unique per registrationId keeps uq_login_provider_type_base_url + the
        // git_provider
        // (type, server_url) key collision-free across the tests in this class.
        provider.setBaseUrl("https://" + registrationId + ".example");
        provider.setClientId("test-client-id");
        provider.replaceClientSecret("test-client-secret", systemSecrets);
        provider.setScopes(type == LoginProvider.ProviderType.OUTLINE ? "read" : "read:user");
        loginProviderRepository.save(provider);
    }

    private static OAuth2User githubPrincipal(String subject, String email, String login) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", subject, "login", login, "email", email, "email_verified", true),
                "id");
    }

    private static OAuth2User outlinePrincipal(String subject, String teamId, String email) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", subject, "team_id", teamId, "team_name", "Acme", "name", "Ada Lovelace", "email", email),
                "id");
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
