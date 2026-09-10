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
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Uses the real issuer and decoder: the JWT identifies an account, not an SCM actor. */
class AccountControllerIntegrationTest extends RealAuthIntegrationTest {

    private static final long GITLAB_NATIVE_ID = 18024L;
    private static final String GITLAB_LOGIN = "gitlabuser";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferencesRepository preferencesRepository;

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

    @Test
    void getUserSettingsProvisionsGitLabUserWhenMissing() {
        assertThat(userRepository.findByLogin(GITLAB_LOGIN)).isEmpty();

        SeededIdentity seeded = seedGitLabLoginAccount(GITLAB_LOGIN);

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

        IdentityLink link =
                identityLinkRepository.findById(seeded.identityLinkId()).orElseThrow();
        assertThat(link.getExternalActorId()).isEqualTo(provisionedUser.getId());
    }

    @Test
    void shouldReadAndUpdateVerifiedActorsSettingsWhenSignupLoginBelongsToAnotherProvider() {
        SeededIdentity seeded = seedGitLabLoginAccount(GITLAB_LOGIN);
        var github = gitProviderRepository.save(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://settings-github.example.com"));
        var namesake = userRepository.save(TestUserFactory.createUser(99L, GITLAB_LOGIN, github));
        var gitlab = gitProviderRepository.findById(seeded.gitProviderId()).orElseThrow();
        var actor = userRepository.save(TestUserFactory.createUser(GITLAB_NATIVE_ID, "renamed-settings-user", gitlab));
        var actorId = Objects.requireNonNull(actor.getId());
        var namesakeId = Objects.requireNonNull(namesake.getId());
        var namesakePreferences = new UserPreferences(namesake);
        namesakePreferences.setParticipateInResearch(true);
        namesakePreferences.setPracticeFeedbackDeliveryEnabled(false);
        preferencesRepository.save(namesakePreferences);
        preferencesRepository.save(new UserPreferences(actor));

        webTestClient
                .get()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(seeded.token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.participateInResearch")
                .isEqualTo(false)
                .jsonPath("$.practiceFeedbackDeliveryEnabled")
                .isEqualTo(true);

        webTestClient
                .post()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(seeded.token()))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(new UserSettingsDTO(true, true))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(preferencesRepository.findByUserId(actorId)).get().satisfies(preferences -> {
            assertThat(preferences.isParticipateInResearch()).isTrue();
            assertThat(preferences.isPracticeFeedbackDeliveryEnabled()).isTrue();
        });
        assertThat(preferencesRepository.findByUserId(namesakeId)).get().satisfies(preferences -> {
            assertThat(preferences.isParticipateInResearch()).isTrue();
            assertThat(preferences.isPracticeFeedbackDeliveryEnabled()).isFalse();
        });
        assertThat(userRepository.findById(actorId))
                .get()
                .extracting(user -> user.getLogin())
                .isEqualTo("renamed-settings-user");
    }

    @Test
    void shouldRejectStaleSignupLoginWithoutRenamingItsNewOwnerWhenActorIsMissing() {
        SeededIdentity seeded = seedGitLabLoginAccount(GITLAB_LOGIN);
        var provider = gitProviderRepository.findById(seeded.gitProviderId()).orElseThrow();
        var namesake = userRepository.save(TestUserFactory.createUser(999L, GITLAB_LOGIN, provider));
        var namesakeId = Objects.requireNonNull(namesake.getId());

        webTestClient
                .get()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(seeded.token()))
                .exchange()
                .expectStatus()
                .isEqualTo(409);

        assertThat(userRepository.findById(namesakeId))
                .get()
                .extracting(user -> user.getLogin())
                .isEqualTo(GITLAB_LOGIN);
        assertThat(userRepository.findByNativeIdAndProviderId(GITLAB_NATIVE_ID, seeded.gitProviderId()))
                .isEmpty();
        assertThat(preferencesRepository.findByUserId(namesakeId)).isEmpty();
    }

    @Test
    void shouldTreatUnderscoresLiterallyWhenCheckingSignupLoginConflicts() {
        SeededIdentity seeded = seedGitLabLoginAccount("gitlab_user");
        var provider = gitProviderRepository.findById(seeded.gitProviderId()).orElseThrow();
        var other = userRepository.save(TestUserFactory.createUser(999L, "gitlabXuser", provider));
        var otherId = Objects.requireNonNull(other.getId());

        webTestClient
                .get()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(seeded.token()))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(userRepository.findByNativeIdAndProviderId(GITLAB_NATIVE_ID, seeded.gitProviderId()))
                .get()
                .extracting(user -> user.getLogin())
                .isEqualTo("gitlab_user");
        assertThat(userRepository.findById(otherId))
                .get()
                .extracting(user -> user.getLogin())
                .isEqualTo("gitlabXuser");
    }

    private record SeededIdentity(String token, long identityLinkId, long gitProviderId) {}

    private SeededIdentity seedGitLabLoginAccount(String signupLogin) {
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
        link.setUsernameAtSignup(signupLogin);
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
