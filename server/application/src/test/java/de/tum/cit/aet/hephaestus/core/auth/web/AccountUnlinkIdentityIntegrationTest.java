package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class AccountUnlinkIdentityIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private PeopleRegistry people;

    @Autowired
    private UserRepository users;

    @Test
    void shouldReadExactProviderProfilesForTheCurrentAccountAndLinkedIdentities() {
        var account = accountRepository.save(new Account("Profile owner"));
        var github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "42");
        var gitlab = seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.com", "42");
        github.setLastLoginAt(Instant.now());
        identityRepository.saveAndFlush(github);
        users.saveAndFlush(TestUserFactory.createUser(
                42,
                "hub-profile",
                gitProviderRepository.findById(github.getProviderId()).orElseThrow()));
        users.saveAndFlush(TestUserFactory.createUser(
                42,
                "lab-profile",
                gitProviderRepository.findById(gitlab.getProviderId()).orElseThrow()));
        String token = tokenFor(account);
        webTestClient
                .get()
                .uri("/user")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.username")
                .isEqualTo("hub-profile");
        webTestClient
                .get()
                .uri("/user/identities")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(AccountWebController.IdentityViewDTO.class)
                .value(views -> assertThat(views)
                        .extracting(AccountWebController.IdentityViewDTO::username)
                        .containsExactlyInAnyOrder("hub-profile", "lab-profile"));
    }

    @Test
    void unlinkSecondaryIdentityDisablesItAndKeepsTheRest() {
        Account account = accountRepository.save(new Account("Two-provider User"));
        Identity github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-1");
        Identity gitlab = seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-1");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(github.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(identityRepository
                        .findById(persistedId(github.getId()))
                        .orElseThrow()
                        .getDisabledAt())
                .isNotNull();
        assertThat(identityRepository.findActiveByAccountId(persistedId(account.getId())))
                .extracting(Identity::getId)
                .containsExactly(persistedId(gitlab.getId()));
    }

    @Test
    void unlinkIsReversibleTheSameProviderCanBeRelinked() {
        Account account = accountRepository.save(new Account("Reversible User"));
        Identity github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-rev");
        seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-rev");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(github.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        var disabled = identityRepository.findById(persistedId(github.getId())).orElseThrow();
        assertThat(disabled.getDisabledAt()).isNotNull();
        long claimed = people.claim(new PersonIdentity(disabled.getProviderId(), disabled.getProviderUserKey(), null));
        assertThat(claimed).isEqualTo(persistedId(account.getId()));
        var relinked = identityRepository.findById(persistedId(github.getId())).orElseThrow();
        assertThat(relinked.getDisabledAt()).isNull();
        assertThat(identityRepository.findActiveByAccountId(persistedId(account.getId())))
                .extracting(Identity::getId)
                .contains(persistedId(relinked.getId()));
    }

    @Test
    void cannotUnlinkTheOnlyRemainingIdentity() {
        Account account = accountRepository.save(new Account("Single-provider User"));
        Identity only = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-only");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(only.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("You cannot disconnect your only sign-in method. Connect another provider"
                        + " first, or delete your account.");

        assertThat(identityRepository.findActiveByAccountId(persistedId(account.getId())))
                .hasSize(1);
    }

    @Test
    void shouldKeepGitHubAndAnOnlyOidcIdentityWhenThisReleaseCannotSignInThroughOidc() {
        Account account = accountRepository.save(new Account("OIDC User"));
        Identity github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-oidc");
        // An issuer can have a path, and its subject is an opaque string.
        Identity oidc = seedLink(
                account,
                IdentityProviderType.OIDC,
                "https://login.example.org/realms/school",
                "3f6c2a1e-9b1d-4f0e-a2c4-5d6e7f8a9b0c");
        Account oidcOnly = accountRepository.save(new Account("OIDC-only User"));
        Identity onlyOidc =
                seedLink(oidcOnly, IdentityProviderType.OIDC, "https://login.example.org/realms/school", "only-oidc");
        String token = tokenFor(account);

        webTestClient
                .get()
                .uri("/user/identities")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(AccountWebController.IdentityViewDTO.class)
                .value(views -> assertThat(views)
                        .extracting(AccountWebController.IdentityViewDTO::providerType)
                        .containsExactlyInAnyOrder("GITHUB", "OIDC"));
        // This release does not sign in through OIDC, so GitHub is the last way in that it serves.
        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(github.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        // A release that signs in through OIDC can still use the only identity of the other account.
        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(onlyOidc.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(oidcOnly)))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(oidc.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(identityRepository.findActiveByAccountId(persistedId(account.getId())))
                .extracting(Identity::getId)
                .containsExactly(persistedId(github.getId()));
        assertThat(identityRepository.findActiveByAccountId(persistedId(oidcOnly.getId())))
                .extracting(Identity::getId)
                .containsExactly(persistedId(onlyOidc.getId()));
    }

    @Test
    void cannotUnlinkAnotherAccountsIdentity() {
        Account me = accountRepository.save(new Account("Me"));
        seedLink(me, IdentityProviderType.GITHUB, "https://github.com", "gh-me");
        seedLink(me, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-me");
        String myToken = tokenFor(me);

        Account other = accountRepository.save(new Account("Other"));
        Identity otherGithub = seedLink(other, IdentityProviderType.GITHUB, "https://github.com", "gh-other");

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(otherGithub.getId()))
                .headers(h -> h.setBearerAuth(myToken))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);

        assertThat(identityRepository.findActiveByAccountId(persistedId(other.getId())))
                .extracting(Identity::getId)
                .containsExactly(persistedId(otherGithub.getId()));
    }

    @Test
    void unlinkingAnUnknownIdentityIs404() {
        Account account = accountRepository.save(new Account("User"));
        seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-x");
        seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-x");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", 9_999_999L)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void concurrentUnlinksCannotDrainTheAccountToZero() throws Exception {
        Account account = accountRepository.save(new Account("Race User"));
        Identity a = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-race");
        Identity b = seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-race");
        String token = tokenFor(account);

        // Fire both unlinks at once. The pessimistic write-lock over the account's active links
        // serializes them, so exactly one wins (204) and the other re-reads count 1 and is rejected
        // (409) — the account always keeps a sign-in method. Without the lock both would pass the
        // guard and the account would be drained to zero.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> first = pool.submit(unlinkStatus(token, persistedId(a.getId()), ready, go));
            Future<Integer> second = pool.submit(unlinkStatus(token, persistedId(b.getId()), ready, go));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            int s1 = first.get(15, TimeUnit.SECONDS);
            int s2 = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(204, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(identityRepository.findActiveByAccountId(persistedId(account.getId())))
                .hasSize(1);
    }

    private Callable<Integer> unlinkStatus(String token, Long identityId, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return webTestClient
                    .delete()
                    .uri("/user/identities/{id}", identityId)
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus()
                    .value();
        };
    }

    private Identity seedLink(Account account, IdentityProviderType type, String serverUrl, String subject) {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> gitProviderRepository.save(new IdentityProvider(type, serverUrl)));
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(persistedId(provider.getId()));
        link.setProviderUserKey(subject);
        return identityRepository.save(link);
    }

    private String tokenFor(Account account) {
        return jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
