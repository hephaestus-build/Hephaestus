package de.tum.cit.aet.hephaestus.core.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private IssuedJwtRepository issuedJwtRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsAccountsAsANumberedPageInAStableOrder() {
        Account reader = persistAdmin("Reading Admin");
        persistAdmin("Second Admin");
        long total = accountRepository.count();

        webTestClient
                .get()
                .uri("/admin/users?page=0&size=1")
                .headers(h -> h.setBearerAuth(tokenFor(reader)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.page")
                .isEqualTo(0)
                .jsonPath("$.size")
                .isEqualTo(1)
                .jsonPath("$.totalElements")
                .isEqualTo(total)
                .jsonPath("$.totalPages")
                .isEqualTo(total);
    }

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
                .isOk()
                .expectBody(Void.class);

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
                .isEqualTo("You cannot revoke your own admin access. Ask another admin to do it.");

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
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldEndSessionsAndRefuseTheOldTokenWhenAnAdminBlocksAnAccount() {
        Account admin = persistAdmin("Blocking Admin");
        Account user = persistUser("Blocked User");
        String userToken = tokenFor(user);
        long userId = persistedId(user.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}/status", userId)
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .bodyValue(Map.of("status", "BLOCKED"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("BLOCKED");

        assertThat(accountRepository.findById(userId))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.BLOCKED);
        assertThat(issuedJwtRepository.findActiveByAccountId(userId, Instant.now()))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT details FROM auth_event WHERE account_id = ? AND acting_account_id = ?"
                                + " AND event_type = 'ACCOUNT_STATUS_CHANGED'",
                        String.class,
                        userId,
                        persistedId(admin.getId())))
                .contains("\"to\":\"BLOCKED\"");
        webTestClient
                .get()
                .uri("/user")
                .headers(h -> h.setBearerAuth(userToken))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldLetABlockedAccountBackInWhenAnAdminUnblocksIt() {
        Account admin = persistAdmin("Unblocking Admin");
        Account user = persistUser("Returning User");
        user.block();
        accountRepository.save(user);
        long userId = persistedId(user.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}/status", userId)
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .bodyValue(Map.of("status", "ACTIVE"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(accountRepository.findById(userId))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldRefuseBlockingFromAPlainUser() {
        Account user = persistUser("Plain Blocker");
        Account target = persistUser("Target");

        webTestClient
                .patch()
                .uri("/admin/users/{id}/status", persistedId(target.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(user)))
                .bodyValue(Map.of("status", "BLOCKED"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(accountRepository.findById(persistedId(target.getId())))
                .get()
                .extracting(Account::getStatus)
                .isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldRejectANullAccountStatusBeforeChangingTheAccount() {
        Account admin = persistAdmin("Validating Admin");
        Account target = persistUser("Unchanged Account");
        long targetId = persistedId(target.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}/status", targetId)
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":null}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(accountRepository.findById(targetId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNCLAIMED", "DELETING", "DELETED"})
    void shouldRejectAStatusOutsideTheAdminRequestContract(String status) {
        Account admin = persistAdmin("Validating Admin");
        Account target = persistUser("Unchanged Account");
        long targetId = persistedId(target.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}/status", targetId)
                .headers(h -> h.setBearerAuth(tokenFor(admin)))
                .bodyValue(Map.of("status", status))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);

        assertThat(accountRepository.findById(targetId).orElseThrow().getStatus())
                .isEqualTo(Account.Status.ACTIVE);
    }

    @Test
    void shouldLetTheOnlyActiveAdminDemoteABlockedAdmin() {
        Account active = persistAdmin("Remaining Admin");
        Account blocked = persistAdmin("Blocked Admin");
        blocked.block();
        accountRepository.saveAndFlush(blocked);
        long blockedId = persistedId(blocked.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", blockedId)
                .headers(h -> h.setBearerAuth(tokenFor(active)))
                .bodyValue(Map.of("appRole", "USER"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(accountRepository.findById(blockedId).orElseThrow().getAppRole())
                .isEqualTo(Account.AppRole.USER);
        assertThat(accountRepository
                        .findById(persistedId(active.getId()))
                        .orElseThrow()
                        .getAppRole())
                .isEqualTo(Account.AppRole.APP_ADMIN);
    }

    @Test
    void shouldKeepAnActiveAdminWhenTwoAdminsBlockEachOtherAtOnce() throws Exception {
        Account adminA = persistAdmin("Blocking Admin A");
        Account adminB = persistAdmin("Blocking Admin B");
        long idA = persistedId(adminA.getId());
        long idB = persistedId(adminB.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> first = pool.submit(block(tokenFor(adminA), idB, ready, go));
            Future<Integer> second = pool.submit(block(tokenFor(adminB), idA, ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(statuses).contains(200);
            assertThat(statuses)
                    .filteredOn(status -> status != 200)
                    .singleElement()
                    .isIn(401, 403, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(List.of(
                        accountRepository.findById(idA).orElseThrow().getStatus(),
                        accountRepository.findById(idB).orElseThrow().getStatus()))
                .containsExactlyInAnyOrder(Account.Status.ACTIVE, Account.Status.BLOCKED);
    }

    private Callable<Integer> block(String token, Long targetId, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            return webTestClient
                    .patch()
                    .uri("/admin/users/{id}/status", targetId)
                    .headers(h -> h.setBearerAuth(token))
                    .bodyValue(Map.of("status", "BLOCKED"))
                    .exchange()
                    .returnResult(Void.class)
                    .getStatus()
                    .value();
        };
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
        return accountRepository.save(account);
    }

    private Account persistUser(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.USER);
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
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
