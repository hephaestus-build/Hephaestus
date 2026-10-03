package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * nOAuth defence against a REAL Postgres: the mock-based {@link AccountProvisioningServiceTest} stubs
 * {@code findActiveByProviderSubject}, so it cannot prove the persisted data keys on (provider,
 * subject) rather than email. Two identities sharing one verified email must resolve to DISTINCT
 * accounts — email is contact metadata, never a join key.
 *
 * <p>The concurrent-first-login convergence is pinned separately by {@code
 * IdentityLinkUniquenessLiquibaseTest} (its {@code COALESCE(team_id,'')} index is unreproducible
 * under this profile's ddl-auto schema); the read-after-conflict recovery is unit-covered by {@link
 * AccountProvisioningServiceTest}.
 *
 * @see <a href="https://www.descope.com/blog/post/noauth">nOAuth: account takeover via email merging</a>
 */
class AccountProvisioningIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private AccountProvisioningService service;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Test
    void twoIdentitiesSharingOneEmailResolveToSeparateAccounts() {
        seedProvider("github-noauth-a", LoginProvider.ProviderType.GITHUB);
        seedProvider("gitlab-noauth-b", LoginProvider.ProviderType.GITLAB);
        String sharedEmail = "victim@shared.example";

        Account viaGithub = service.resolveOrProvision(
                        "github-noauth-a",
                        "gh-subject-victim",
                        principal("gh-subject-victim", sharedEmail, true, "victim"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();
        // A DIFFERENT provider + subject carrying the SAME verified email — the nOAuth attack shape.
        Account viaGitlab = service.resolveOrProvision(
                        "gitlab-noauth-b",
                        "gl-subject-attacker",
                        principal("gl-subject-attacker", sharedEmail, true, "attacker"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        // Two distinct accounts: the shared email did NOT fold the second identity into the first.
        assertThat(persistedId(viaGitlab.getId())).isNotEqualTo(persistedId(viaGithub.getId()));
        // Both store the email as contact metadata (proving it WAS seen, just never used as a key).
        assertThat(viaGithub.getPrimaryEmail()).isEqualTo(sharedEmail);
        assertThat(viaGitlab.getPrimaryEmail()).isEqualTo(sharedEmail);
        // Each account owns exactly its own identity link, on its own provider+subject.
        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(viaGithub.getId())))
                .extracting(IdentityLink::getSubject)
                .containsExactly("gh-subject-victim");
        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(viaGitlab.getId())))
                .extracting(IdentityLink::getSubject)
                .containsExactly("gl-subject-attacker");
    }

    @Test
    void sameProviderDifferentSubjectSharingOneEmailAlsoSeparates() {
        seedProvider("github-samesubj", LoginProvider.ProviderType.GITHUB);
        String sharedEmail = "two-accounts@shared.example";

        Account first = service.resolveOrProvision(
                        "github-samesubj",
                        "subject-one",
                        principal("subject-one", sharedEmail, true, "one"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();
        Account second = service.resolveOrProvision(
                        "github-samesubj",
                        "subject-two",
                        principal("subject-two", sharedEmail, true, "two"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        // Even within ONE provider, the subject is the key — a shared email never merges subjects.
        assertThat(persistedId(second.getId())).isNotEqualTo(persistedId(first.getId()));
    }

    @Test
    void returningLoginWithSameProviderSubjectResolvesTheSameAccount() {
        seedProvider("github-returning", LoginProvider.ProviderType.GITHUB);

        Account firstLogin = service.resolveOrProvision(
                        "github-returning",
                        "returning-subject",
                        principal("returning-subject", "ada@returning.example", true, "ada"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();
        // A second login for the SAME (provider, subject) must reuse the account, not JIT a new one —
        // even though the IdP now reports a different email (people change emails; the link is the key).
        Account secondLogin = service.resolveOrProvision(
                        "github-returning",
                        "returning-subject",
                        principal("returning-subject", "ada-new@returning.example", true, "ada"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();

        assertThat(persistedId(secondLogin.getId())).isEqualTo(persistedId(firstLogin.getId()));
        // Idempotent: still exactly one identity link for the account.
        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(firstLogin.getId())))
                .hasSize(1);
    }

    @Test
    void deletingAccountCannotAttachANewNativeIdentityOrUpdateAnExistingLink() {
        seedProvider("github-deleting", LoginProvider.ProviderType.GITHUB);
        Account account = service.resolveOrProvision(
                        "github-deleting",
                        "original-native-subject",
                        principal("original-native-subject", "contact@example.test", true, "display"),
                        AuthIntentCookie.Intent.login(null, null))
                .account();
        long accountId = persistedId(account.getId());
        account.setStatus(Account.Status.DELETING);
        accountRepository.saveAndFlush(account);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveOrProvision(
                        "github-deleting",
                        "new-native-subject",
                        principal("new-native-subject", "contact@example.test", true, "display"),
                        AuthIntentCookie.Intent.link(accountId, null)))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveOrProvision(
                        "github-deleting",
                        "original-native-subject",
                        principal("original-native-subject", "contact@example.test", true, "display"),
                        AuthIntentCookie.Intent.login(null, null)))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(identityLinkRepository.findActiveByAccountId(accountId))
                .extracting(IdentityLink::getSubject)
                .containsExactly("original-native-subject");
    }

    private void seedProvider(String registrationId, LoginProvider.ProviderType type) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        provider.setDisplayName(registrationId);
        // A baseUrl unique per registrationId keeps both uq_login_provider_type_base_url and the
        // git_provider (type, server_url) key collision-free across the tests in this class.
        provider.setBaseUrl("https://" + registrationId + ".example");
        provider.setClientId("test-client-id");
        provider.setClientSecret("test-client-secret");
        provider.setScopes("read:user");
        loginProviderRepository.save(provider);
    }

    private static OAuth2User principal(String subject, String email, boolean emailVerified, String login) {
        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", subject, "login", login, "email", email, "email_verified", emailVerified),
                "id");
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
