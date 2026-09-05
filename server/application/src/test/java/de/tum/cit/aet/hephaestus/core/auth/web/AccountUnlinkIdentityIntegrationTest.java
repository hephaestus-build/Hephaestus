package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.List;
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

/**
 * {@code DELETE /user/identities/{id}} — unlinking a federated identity from the current account.
 *
 * <p>Uses the <b>real</b> {@code RevocationAwareJwtDecoder} + a genuine ES256 cookie-JWT (no mock
 * decoder), mirroring {@code AccountControllerIntegrationTest}. The bearer header path skips CSRF
 * (a pure bearer request carries no auth cookie), so these calls authenticate by token alone.
 *
 * <p>Covers the two safety rules every account-linking UI needs: ownership (you can only unlink your
 * own identity) and last-identity lockout prevention (you cannot remove your only sign-in method).
 */
class AccountUnlinkIdentityIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

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
    void unlinkSecondaryIdentityDeletesItAndKeepsTheRest() {
        Account account = accountRepository.save(new Account("Two-provider User"));
        IdentityLink github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-1", "octocat");
        IdentityLink gitlab = seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-1", "gluser");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(github.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent();

        // Hard delete: the unlinked row is gone (not soft-disabled), the other identity stays active.
        assertThat(identityLinkRepository.findById(persistedId(github.getId()))).isEmpty();
        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(account.getId())))
                .extracting(IdentityLink::getId)
                .containsExactly(persistedId(gitlab.getId()));
    }

    @Test
    void unlinkIsReversibleTheSameProviderCanBeRelinked() {
        Account account = accountRepository.save(new Account("Reversible User"));
        IdentityLink github = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-rev", "rev");
        seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-rev", "rev-gl");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(github.getId()))
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNoContent();

        // The row is gone, so the global (provider, subject) uniqueness is freed and the SAME GitHub
        // identity can be linked again — the promise the disconnect dialog makes. A soft-delete
        // regression would leave the key occupied and this re-link would throw a unique violation.
        assertThat(identityLinkRepository.findById(persistedId(github.getId()))).isEmpty();
        IdentityLink relinked = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-rev", "rev");
        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(account.getId())))
                .extracting(IdentityLink::getId)
                .contains(persistedId(relinked.getId()));
    }

    @Test
    void cannotUnlinkTheOnlyRemainingIdentity() {
        Account account = accountRepository.save(new Account("Single-provider User"));
        IdentityLink only = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-only", "soloist");
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
                .isEqualTo(
                        "You can't unlink your only sign-in method. Link another provider first, or delete your account.");

        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(account.getId())))
                .hasSize(1);
    }

    @Test
    void cannotUnlinkAnotherAccountsIdentity() {
        Account me = accountRepository.save(new Account("Me"));
        seedLink(me, IdentityProviderType.GITHUB, "https://github.com", "gh-me", "me");
        seedLink(me, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-me", "me-gl");
        String myToken = tokenFor(me);

        Account other = accountRepository.save(new Account("Other"));
        IdentityLink otherGithub =
                seedLink(other, IdentityProviderType.GITHUB, "https://github.com", "gh-other", "other");

        webTestClient
                .delete()
                .uri("/user/identities/{id}", persistedId(otherGithub.getId()))
                .headers(h -> h.setBearerAuth(myToken))
                .exchange()
                .expectStatus()
                .isNotFound();

        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(other.getId())))
                .extracting(IdentityLink::getId)
                .containsExactly(persistedId(otherGithub.getId()));
    }

    @Test
    void unlinkingAnUnknownIdentityIs404() {
        Account account = accountRepository.save(new Account("User"));
        seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-x", "x");
        seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-x", "x-gl");
        String token = tokenFor(account);

        webTestClient
                .delete()
                .uri("/user/identities/{id}", 9_999_999L)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void concurrentUnlinksCannotDrainTheAccountToZero() throws Exception {
        Account account = accountRepository.save(new Account("Race User"));
        IdentityLink a = seedLink(account, IdentityProviderType.GITHUB, "https://github.com", "gh-race", "race");
        IdentityLink b = seedLink(account, IdentityProviderType.GITLAB, "https://gitlab.lrz.de", "gl-race", "race-gl");
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

        assertThat(identityLinkRepository.findActiveByAccountId(persistedId(account.getId())))
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

    private IdentityLink seedLink(
            Account account, IdentityProviderType type, String serverUrl, String subject, String login) {
        IdentityProvider provider = gitProviderRepository
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> gitProviderRepository.save(new IdentityProvider(type, serverUrl)));
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(persistedId(provider.getId()));
        link.setSubject(subject);
        link.setUsernameAtSignup(login);
        link.setDisplayName(login);
        return identityLinkRepository.save(link);
    }

    private String tokenFor(Account account) {
        return jwtIssuer
                .issue(principalFactory.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
