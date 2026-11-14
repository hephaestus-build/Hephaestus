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

class SlackIdentityIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private SystemSecrets systemSecrets;

    @Autowired
    private AccountProvisioningService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Test
    void linkingSlackAttachesExactlyOneSlackIdentityToTheSameAccount() {
        seedProvider("github-slacklink", LoginProvider.ProviderType.GITHUB);
        seedProvider("slack-link", LoginProvider.ProviderType.SLACK);

        // A developer signs in with GitHub → their base account.
        Account github = service.resolveOrProvision(
                        "github-slacklink",
                        "gh-1",
                        githubPrincipal("gh-1", "dev@example.com", "octocat"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        // While authenticated, they link "Sign in with Slack" (simulated id_token: sub=U1,
        // team_id=T1).
        AccountProvisioningService.ProvisionResult linked = service.resolveOrProvision(
                "slack-link",
                "U1",
                slackPrincipal("U1", "T1", "dev@example.com"),
                AuthIntentCookie.Intent.link(persistedId(github.getId()), "/settings"));

        assertThat(linked.identityLinked()).isTrue();
        assertThat(linked.account().getId()).isEqualTo(persistedId(github.getId()));
        assertThat(accountRepository.findById(persistedId(github.getId()))).isPresent();

        // Exactly one SLACK identity link (subject U1, team T1, MANUAL_LINK) now hangs off the SAME
        // account.
        List<Identity> links = identityRepository.findActiveByAccountId(persistedId(github.getId()));
        assertThat(links).extracting(Identity::getProviderUserKey).containsExactlyInAnyOrder("gh-1", "U1");
        Identity slack = links.stream()
                .filter(l -> l.getProviderUserKey().equals("U1"))
                .findFirst()
                .orElseThrow();
        assertThat(slack.getTeamKey()).isEqualTo("T1");
        assertThat(slack.getLinkedVia()).isEqualTo(Identity.LinkedVia.MANUAL);
    }

    @Test
    void unauthenticatedSlackLinkStartCreatesNoAccount() {
        seedProvider("slack-noauth", LoginProvider.ProviderType.SLACK);

        // A LINK-mode Slack callback with no authenticated account binding must fail closed — never
        // an orphan.
        assertThatThrownBy(() -> service.resolveOrProvision(
                        "slack-noauth",
                        "U1",
                        slackPrincipal("U1", "T1", "dev@example.com"),
                        AuthIntentCookie.Intent.link(null, "/settings")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(accountRepository.findByPrimaryEmail("dev@example.com")).isEmpty();
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
        provider.setScopes(type == LoginProvider.ProviderType.SLACK ? "openid profile email" : "read:user");
        loginProviderRepository.save(provider);
    }

    private static OAuth2User githubPrincipal(String subject, String email, String login) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", subject, "login", login, "email", email, "email_verified", true),
                "id");
    }

    private static OAuth2User slackPrincipal(String subject, String teamId, String email) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "sub",
                        subject,
                        "https://slack.com/team_id",
                        teamId,
                        "name",
                        "Slack Dev",
                        "email",
                        email,
                        "email_verified",
                        true),
                "sub");
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
