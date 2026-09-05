package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies that {@code GET /user/settings} provisions the SCM {@code User} for a GitLab login-only
 * principal from the account's federated identities — the production path after the Keycloak →
 * native cookie-JWT cutover.
 *
 * <p>Deliberately uses the <b>real</b> {@code RevocationAwareJwtDecoder} (it does NOT import
 * {@code TestSecurityConfig}'s mock decoder, which used to inject a synthetic {@code gitlab_id}
 * claim that masked the bug) and mints a genuine ES256 cookie-JWT through {@link HephaestusJwtIssuer}
 * whose only identity claim is {@code sub = Account.id}. Resolution is therefore
 * {@code sub → Account → active GitLab IdentityLink → User} end-to-end.
 */
class AccountControllerIntegrationTest extends RealAuthIntegrationTest {

    private static final long GITLAB_NATIVE_ID = 18024L;
    private static final String GITLAB_LOGIN = "gitlabuser";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    /**
     * A GitLab login-only user with no pre-existing SCM {@code User} row must get one provisioned
     * (no 404). If production resolution regresses to reading the absent {@code gitlab_id} claim,
     * no user is provisioned and {@code GET /user/settings} returns 404 — failing this test.
     */
    @Test
    void getUserSettingsProvisionsGitLabUserWhenMissing() {
        assertThat(userRepository.findByLogin(GITLAB_LOGIN)).isEmpty();

        SeededIdentity seeded = seedGitLabLoginAccount();

        webTestClient
                .get()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(seeded.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.practiceFeedbackDeliveryEnabled")
                .isEqualTo(true)
                .jsonPath("$.participateInResearch")
                .isEqualTo(false);

        var provisionedUser = userRepository.findByLogin(GITLAB_LOGIN).orElseThrow();
        assertThat(provisionedUser.getNativeId()).isEqualTo(GITLAB_NATIVE_ID);
        // The provider FK is lazy on the detached User; resolve it eagerly via the repository to
        // assert type / server URL without an open session.
        IdentityProvider provider =
                gitProviderRepository.findById(seeded.gitProviderId()).orElseThrow();
        assertThat(provider.getType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(provider.getServerUrl()).isEqualTo(gitLabProperties.defaultServerUrl());

        // The IdentityLink → ExternalActor wiring gap is closed: the link now points at the mirror.
        IdentityLink link =
                identityLinkRepository.findById(seeded.identityLinkId()).orElseThrow();
        assertThat(link.getExternalActorId()).isEqualTo(provisionedUser.getId());
    }

    private record SeededIdentity(String token, long identityLinkId, long gitProviderId) {}

    private SeededIdentity seedGitLabLoginAccount() {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, gitLabProperties.defaultServerUrl())));

        Long providerId = Objects.requireNonNull(provider.getId(), "Persisted identity provider must have an ID");
        Account account = accountRepository.save(new Account("GitLab User"));

        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(providerId);
        link.setSubject(String.valueOf(GITLAB_NATIVE_ID));
        link.setUsernameAtSignup(GITLAB_LOGIN);
        link.setDisplayName("GitLab User");
        link = identityLinkRepository.save(link);

        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccount(account), TokenConstraints.session(null, Instant.now()), null);
        return new SeededIdentity(
                token.value(),
                Objects.requireNonNull(link.getId(), "Persisted identity link must have an ID"),
                providerId);
    }
}
