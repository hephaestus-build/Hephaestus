package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * {@code PATCH /admin/users/{id}} — the super-admin role change against the <b>real</b> security
 * chain (genuine cookie-JWT, no mock decoder), mirroring {@code AccountUnlinkIdentityIntegrationTest}.
 *
 * <p>Covers the last-admin lockout guard in {@code AccountService.adminSetRole}.
 * {@link #concurrentDemotionsCannotDrainTheRoleToZero} would fail if the {@code @Lock} were dropped:
 * without it, two admins demoting each other both read count==2 from their own snapshot and both
 * commit, draining the role to zero. (The two updates touch distinct rows, so {@code @Version} alone
 * does not catch it — the {@code FOR UPDATE} over the {@code (APP_ADMIN, ACTIVE)} set is what
 * serializes them.) Requires Docker, like every {@code @Tag("integration")} test.
 */
class AccountAdminRoleIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private JwtPrincipalFactory principalFactory;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Test
    void demotingOneAdminWhileAnotherRemainsPersistsToTheDatabase() {
        Account keeper = persistAdmin("Keeper Admin");
        Account victim = persistAdmin("Demote Me");

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(victim.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(keeper)))
                .bodyValue(Map.of("appRole", "USER"))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(accountRepository.findById(persistedId(victim.getId())))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.USER);
        assertThat(activeAdminCount()).isEqualTo(1L);
    }

    @Test
    void cannotRevokeYourOwnAdminEvenWhenAnotherAdminExists() {
        Account self = persistAdmin("Self Admin");
        persistAdmin("Backup Admin"); // another admin exists, yet self-demotion is still refused

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(self.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(self)))
                .bodyValue(Map.of("appRole", "USER"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("You can't revoke your own admin access. Have another admin do it.");

        assertThat(accountRepository.findById(persistedId(self.getId())))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.APP_ADMIN);
    }

    @Test
    void concurrentDemotionsCannotDrainTheRoleToZero() throws Exception {
        Account adminA = persistAdmin("Admin A");
        Account adminB = persistAdmin("Admin B");
        assertThat(activeAdminCount()).isEqualTo(2L);

        // A demotes B and B demotes A at the same instant. The pessimistic write-lock over the
        // (APP_ADMIN, ACTIVE) set serializes them: exactly one wins (200), the loser re-reads count 1
        // and is rejected (409). Without the lock both would pass the guard and drain the role to zero.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> first = pool.submit(demote(tokenFor(adminA), persistedId(adminB.getId()), ready, go));
            Future<Integer> second = pool.submit(demote(tokenFor(adminB), persistedId(adminA.getId()), ready, go));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            int s1 = first.get(15, TimeUnit.SECONDS);
            int s2 = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(activeAdminCount()).isEqualTo(1L);
    }

    @Test
    void adminForceSignOutRevokesTargetSessions() {
        Account admin = persistAdmin("Admin");
        Account user = persistUser("Plain User");
        tokenFor(user); // mints + records an active issued_jwt for the user
        assertThat(issuedJwtRepository.findActiveByAccountId(persistedId(user.getId()), Instant.now()))
                .hasSize(1);

        // Admin force sign-out revokes the user's active session(s).
        webTestClient
                .delete()
                .uri("/admin/users/{id}/sessions", persistedId(user.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.revoked")
                .isEqualTo(1);

        // The account now has no active sessions — RevocationAwareJwtDecoder rejects the token on its
        // next request (enforced per-request via the issued_jwt revocation row).
        assertThat(issuedJwtRepository.findActiveByAccountId(persistedId(user.getId()), Instant.now()))
                .isEmpty();
    }

    @Test
    void forceSignOutRequiresAdmin() {
        Account user = persistUser("Plain User");

        webTestClient
                .delete()
                .uri("/admin/users/{id}/sessions", persistedId(user.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(user)))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    private Callable<Integer> demote(String token, Long targetId, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return webTestClient
                    .patch()
                    .uri("/admin/users/{id}", targetId)
                    .headers(h -> h.setBearerAuth(token))
                    .bodyValue(Map.of("appRole", "USER"))
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus()
                    .value();
        };
    }

    private Account persistAdmin(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.APP_ADMIN);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private Account persistUser(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.USER);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    // Plain (non-locking) count for assertions. The production guard's query is @Lock(PESSIMISTIC_WRITE)
    // and so requires an active transaction; counting here via findAll avoids needing one in the test.
    private long activeAdminCount() {
        return accountRepository.findAll().stream()
                .filter(a -> a.getAppRole() == Account.AppRole.APP_ADMIN && a.getStatus() == Account.Status.ACTIVE)
                .count();
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
