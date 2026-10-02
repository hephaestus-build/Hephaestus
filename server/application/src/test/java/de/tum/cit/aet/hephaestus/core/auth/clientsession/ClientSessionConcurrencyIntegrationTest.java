package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInFlow.CALLBACK;
import static de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInFlow.CLIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import de.tum.cit.aet.hephaestus.core.auth.AccountPurger;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.SessionRevocation;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the one lock order — account, then client session, then issued tokens — against real PostgreSQL
 * transactions. Each case runs the first operation to completion inside a transaction that stays open,
 * starts the second on another thread, waits until PostgreSQL reports it blocked on a row lock, then
 * commits the first. Every pair is run in both acquisition orders; both must finish without a deadlock
 * abort, and once a revocation has committed no token of the family authenticates or refreshes.
 */
@TestPropertySource(properties = "hephaestus.auth.dev-login-enabled=true")
class ClientSessionConcurrencyIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClientSessionService clientSessions;

    @Autowired
    private SessionRevocation sessionRevocation;

    @Autowired
    private AuthSessionService authSessions;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private AccountPurger accountPurger;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private ClientSessionRepository sessionRepository;

    @Autowired
    private ClientSessionPruner pruner;

    @Autowired
    private InstalledClientRegistry registry;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookieName;

    private ClientSignInFlow flow;
    private TransactionTemplate tx;

    @BeforeEach
    void setUpFlow() {
        flow = new ClientSignInFlow(webTestClient);
        tx = new TransactionTemplate(transactionManager);
    }

    // ── refresh vs single-session revoke ────────────────────────────────────────────────────────────

    @Test
    void shouldEndTheRotatedFamilyWhenAStaleEntryRevokeWaitsBehindARefresh() throws Exception {
        ClientSignInFlow.Tokens r0 = flow.signIn("race-single-a");
        UUID j0 = jtiOf(r0.accessToken());
        Long accountId = accountOf(r0);

        Outcome<Optional<ClientSessionService.ClientTokens>, Boolean> outcome =
                inOrder(() -> clientSessions.refresh(r0.refreshToken(), null), () -> {
                    authSessions.revokeSession(accountId, j0);
                    return true;
                });

        ClientSessionService.ClientTokens rotated = outcome.first().orElseThrow();
        assertFamilyDead(rotated.accessToken(), rotated.refreshToken());
    }

    @Test
    void shouldRefuseTheRefreshWhenItWaitsBehindASingleSessionRevoke() throws Exception {
        ClientSignInFlow.Tokens r0 = flow.signIn("race-single-b");
        UUID j0 = jtiOf(r0.accessToken());
        Long accountId = accountOf(r0);

        Outcome<Boolean, Optional<ClientSessionService.ClientTokens>> outcome = inOrder(
                () -> {
                    authSessions.revokeSession(accountId, j0);
                    return true;
                },
                () -> clientSessions.refresh(r0.refreshToken(), null));

        assertThat(outcome.second()).isEmpty();
        assertFamilyDead(r0.accessToken(), r0.refreshToken());
    }

    // ── two refreshes presenting the same current secret ────────────────────────────────────────────

    @Test
    void shouldMintOnceAndThenEndTheFamilyWhenTheSameSecretIsRefreshedTwiceConcurrently() throws Exception {
        ClientSignInFlow.Tokens r0 = flow.signIn("race-same-secret");
        UUID sid = sidOf(r0.accessToken());

        Outcome<Optional<ClientSessionService.ClientTokens>, Optional<ClientSessionService.ClientTokens>> outcome =
                inOrder(
                        () -> clientSessions.refresh(r0.refreshToken(), null),
                        () -> clientSessions.refresh(r0.refreshToken(), null));

        ClientSessionService.ClientTokens minted = outcome.first().orElseThrow();
        assertThat(outcome.second()).isEmpty();
        assertThat(sessionRepository.findById(sid).orElseThrow().getRevokedReason())
                .isEqualTo(IssuedJwt.RevokedReason.REFRESH_REUSE);
        assertFamilyDead(minted.accessToken(), minted.refreshToken());
        flow.assertRejected(r0.accessToken());
    }

    // ── refresh vs account erasure ──────────────────────────────────────────────────────────────────

    @Test
    void shouldEraseTheTokenARefreshMintedWhenAPurgeWaitsBehindIt() throws Exception {
        ClientSignInFlow.Tokens tokens = flow.signIn("race-purge-a");
        Long accountId = accountOf(tokens);
        UUID sid = sidOf(tokens.accessToken());

        Outcome<Optional<ClientSessionService.ClientTokens>, Boolean> outcome =
                inOrder(() -> clientSessions.refresh(tokens.refreshToken(), null), () -> {
                    accountPurger.purge(accountId);
                    return true;
                });

        ClientSessionService.ClientTokens minted = outcome.first().orElseThrow();
        flow.assertRejected(minted.accessToken());
        flow.assertRefreshRefused(minted.refreshToken());
        assertErased(accountId, sid);
    }

    /**
     * The purge is held after it has taken the account row (a row lock on one of the account's feature
     * rows stalls its first delete), so the refresh is proven to wait on the purge's account lock.
     */
    @Test
    void shouldRefuseTheRefreshWhenItWaitsBehindAPurgeThatHoldsTheAccount() throws Exception {
        ClientSignInFlow.Tokens tokens = flow.signIn("race-purge-b");
        Long accountId = accountOf(tokens);
        UUID sid = sidOf(tokens.accessToken());
        accountFeatureRepository.save(new AccountFeature(accountId, "mentor_access"));

        CountDownLatch featureHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> blocker = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForList("SELECT flag FROM account_feature WHERE account_id = ? FOR UPDATE", accountId);
                featureHeld.countDown();
                await(release);
                return true;
            }));
            assertThat(featureHeld.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> purge = pool.submit(() -> {
                accountPurger.purge(accountId);
                return true;
            });
            awaitLockWaiters(1, purge);
            Future<Optional<ClientSessionService.ClientTokens>> refresh =
                    pool.submit(() -> clientSessions.refresh(tokens.refreshToken(), null));
            awaitLockWaiters(2, refresh);
            release.countDown();

            assertThat(blocker.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(purge.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(refresh.get(20, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        flow.assertRejected(tokens.accessToken());
        flow.assertRefreshRefused(tokens.refreshToken());
        assertErased(accountId, sid);
    }

    private void assertErased(Long accountId, UUID sid) {
        assertThat(sessionRepository.findById(sid)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_jwt WHERE account_id = ?", Long.class, accountId))
                .isZero();
    }

    // ── refresh vs revoke-all / revoke-all-except ───────────────────────────────────────────────────

    @Test
    void shouldRevokeTheTokenARefreshMintedWhenSignOutEverywhereWaitsBehindIt() throws Exception {
        ClientSignInFlow.Tokens target = flow.signIn("race-all-a");
        ClientSignInFlow.Tokens keeper = flow.signIn("race-all-a");
        Long accountId = accountOf(target);

        Outcome<Optional<ClientSessionService.ClientTokens>, Boolean> outcome =
                inOrder(() -> clientSessions.refresh(target.refreshToken(), null), () -> {
                    authSessions.revokeAllExcept(accountId, jtiOf(keeper.accessToken()), sidOf(keeper.accessToken()));
                    return true;
                });

        ClientSessionService.ClientTokens rotated = outcome.first().orElseThrow();
        assertFamilyDead(rotated.accessToken(), rotated.refreshToken());
        flow.assertAccepted(keeper.accessToken());
        flow.rotate(keeper.refreshToken());
    }

    @Test
    void shouldRefuseTheRefreshWhenItWaitsBehindSignOutEverywhere() throws Exception {
        ClientSignInFlow.Tokens target = flow.signIn("race-all-b");
        Long accountId = accountOf(target);

        Outcome<Integer, Optional<ClientSessionService.ClientTokens>> outcome = inOrder(
                () -> sessionRevocation.revokeAccount(
                        accountId, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, null),
                () -> clientSessions.refresh(target.refreshToken(), null));

        assertThat(outcome.second()).isEmpty();
        assertFamilyDead(target.accessToken(), target.refreshToken());
    }

    @Test
    void shouldRevokeTheTokenARefreshMintedWhenAnAdministratorsRevokeAllWaitsBehindIt() throws Exception {
        ClientSignInFlow.Tokens target = flow.signIn("race-admin-a");
        Long accountId = accountOf(target);

        Outcome<Optional<ClientSessionService.ClientTokens>, Integer> outcome = inOrder(
                () -> clientSessions.refresh(target.refreshToken(), null),
                () -> accountService.adminRevokeAllSessions(accountId, accountId));

        ClientSessionService.ClientTokens rotated = outcome.first().orElseThrow();
        assertThat(outcome.second()).isPositive();
        assertFamilyDead(rotated.accessToken(), rotated.refreshToken());
    }

    // ── refresh vs the account losing its standing (deletion; no other path suspends an account) ──

    @Test
    void shouldRevokeTheTokenARefreshMintedWhenAccountDeletionWaitsBehindIt() throws Exception {
        ClientSignInFlow.Tokens target = flow.signIn("race-delete-a");
        Long accountId = accountOf(target);

        Outcome<Optional<ClientSessionService.ClientTokens>, Boolean> outcome =
                inOrder(() -> clientSessions.refresh(target.refreshToken(), null), () -> {
                    accountService.softDelete(accountId);
                    return true;
                });

        ClientSessionService.ClientTokens rotated = outcome.first().orElseThrow();
        assertFamilyDead(rotated.accessToken(), rotated.refreshToken());
    }

    @Test
    void shouldRefuseTheRefreshWhenItWaitsBehindAccountDeletion() throws Exception {
        ClientSignInFlow.Tokens target = flow.signIn("race-delete-b");
        Long accountId = accountOf(target);

        Outcome<Boolean, Optional<ClientSessionService.ClientTokens>> outcome = inOrder(
                () -> {
                    accountService.softDelete(accountId);
                    return true;
                },
                () -> clientSessions.refresh(target.refreshToken(), null));

        assertThat(outcome.second()).isEmpty();
        assertFamilyDead(target.accessToken(), target.refreshToken());
    }

    // ── exchange and handoff creation vs revoke-all ─────────────────────────────────────────────────

    @Test
    void shouldEndTheNewSessionWhenRevokeAllWaitsBehindAnExchange() throws Exception {
        ClientSignInFlow.Handoff handoff = flow.handoff("race-exchange-a");
        Long accountId = accountIdOf("race-exchange-a");

        Outcome<Optional<ClientSessionService.ClientTokens>, Integer> outcome = inOrder(
                () -> clientSessions.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier(), null),
                () -> sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null));

        ClientSessionService.ClientTokens tokens = outcome.first().orElseThrow();
        assertFamilyDead(tokens.accessToken(), tokens.refreshToken());
    }

    @Test
    void shouldRefuseTheExchangeWhenItWaitsBehindRevokeAll() throws Exception {
        ClientSignInFlow.Handoff handoff = flow.handoff("race-exchange-b");
        Long accountId = accountIdOf("race-exchange-b");

        Outcome<Integer, Optional<ClientSessionService.ClientTokens>> outcome = inOrder(
                () -> sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null),
                () -> clientSessions.exchange(CLIENT_ID, CALLBACK, handoff.code(), handoff.verifier(), null));

        assertThat(outcome.second()).isEmpty();
        assertThat(sessionRepository.findLiveByAccountId(accountId, Instant.now()))
                .isEmpty();
    }

    @Test
    void shouldBurnAHandoffWhenRevokeAllWaitsBehindItsCreation() throws Exception {
        flow.handoff("race-create-a");
        Long accountId = accountIdOf("race-create-a");
        String verifier = Pkce.newSecret();

        Outcome<Optional<String>, Integer> outcome = inOrder(
                () -> createHandoff(accountId, verifier),
                () -> sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null));

        String code = outcome.first().orElseThrow();
        flow.exchange(CLIENT_ID, CALLBACK, code, verifier)
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    /**
     * A handoff whose creation had to wait for the revocation is a sign-in that completed after it:
     * account-wide revocation ends existing sessions, it does not bar the account from signing in again.
     */
    @Test
    void shouldCreateTheHandoffAfterwardsWhenItsCreationWaitsBehindRevokeAll() throws Exception {
        ClientSignInFlow.Tokens earlier = flow.signIn("race-create-b");
        Long accountId = accountOf(earlier);
        String verifier = Pkce.newSecret();

        Outcome<Integer, Optional<String>> outcome = inOrder(
                () -> sessionRevocation.revokeAccount(accountId, IssuedJwt.RevokedReason.ADMIN_REVOKE, null, null),
                () -> createHandoff(accountId, verifier));

        assertFamilyDead(earlier.accessToken(), earlier.refreshToken());
        String code = outcome.second().orElseThrow();
        flow.exchange(CLIENT_ID, CALLBACK, code, verifier).expectStatus().isOk().expectBody(Void.class);
    }

    // ── demotion vs issuance: the principal is read under the lock ─────────────────────────────────

    @Test
    void shouldMintTheDemotedRoleWhenAnIssuanceWaitsBehindADemotion() throws Exception {
        Long target = admin("race-demote-a-target");
        Long actor = admin("race-demote-a-actor");

        Outcome<Account, HephaestusJwtIssuer.Token> outcome = inOrder(
                () -> accountService.adminSetRole(target, "USER", actor),
                () -> jwtIssuer.issue(
                        target,
                        TokenConstraints.session(Instant.now().plus(Duration.ofHours(1)), Instant.now()),
                        null));

        Jwt minted = jwtDecoder.decode(outcome.second().value());
        assertThat(minted.getClaimAsStringList("roles")).doesNotContain("app_admin");
    }

    @Test
    void shouldRevokeTheAdminTokenWhenADemotionWaitsBehindItsIssuance() throws Exception {
        Long target = admin("race-demote-b-target");
        Long actor = admin("race-demote-b-actor");

        Outcome<HephaestusJwtIssuer.Token, Account> outcome = inOrder(
                () -> jwtIssuer.issue(
                        target, TokenConstraints.session(Instant.now().plus(Duration.ofHours(1)), Instant.now()), null),
                () -> accountService.adminSetRole(target, "USER", actor));

        assertThat(outcome.second().getAppRole()).isEqualTo(Account.AppRole.USER);
        flow.assertRejected(outcome.first().value());
    }

    // ── browser cookie refresh vs revoke-all ────────────────────────────────────────────────────────

    @Test
    void shouldRevokeTheRotatedCookieTokenWhenRevokeAllWaitsBehindACookieRefresh() throws Exception {
        String cookie = webLogin("race-cookie-a");
        Jwt token = jwtDecoder.decode(cookie);
        Long accountId = Long.parseLong(Objects.requireNonNull(token.getSubject()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Outcome<Boolean, Integer> outcome = inOrder(
                () -> authSessions.refresh(
                        accountId, jtiOf(cookie), constraintsOf(token), new MockHttpServletRequest(), response),
                () -> sessionRevocation.revokeAccount(
                        accountId, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, null));

        assertThat(outcome.first()).isTrue();
        String rotated = Objects.requireNonNull(response.getCookie(cookieName)).getValue();
        flow.assertRejected(rotated);
        flow.assertRejected(cookie);
    }

    @Test
    void shouldMintNothingWhenACookieRefreshWaitsBehindRevokeAll() throws Exception {
        String cookie = webLogin("race-cookie-b");
        Jwt token = jwtDecoder.decode(cookie);
        Long accountId = Long.parseLong(Objects.requireNonNull(token.getSubject()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        inOrder(
                () -> sessionRevocation.revokeAccount(
                        accountId, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, null),
                () -> authSessions.refresh(
                        accountId, jtiOf(cookie), constraintsOf(token), new MockHttpServletRequest(), response));

        assertThat(response.getCookie(cookieName)).isNull();
        flow.assertRejected(cookie);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM issued_jwt WHERE account_id = ? AND revoked_at IS NULL",
                        Long.class,
                        accountId))
                .isZero();
    }

    // ── expired-session refresh vs cleanup: same lock order, never a deadlock ──────────────────────

    @Test
    void shouldSkipTheLockedSessionWhenCleanupRunsWhileAnExpiredRefreshHoldsIt() throws Exception {
        ClientSignInFlow.Tokens tokens = flow.signIn("race-cleanup-a");
        UUID sid = sidOf(tokens.accessToken());
        expire(sid);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<ClientSessionService.ClientTokens>> refresh = pool.submit(() -> tx.execute(status -> {
                try {
                    return clientSessions.refresh(tokens.refreshToken(), null);
                } finally {
                    held.countDown();
                    await(release);
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            pruner.prune();

            assertThat(sessionRepository.findById(sid)).isPresent();
            release.countDown();
            assertThat(refresh.get(20, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        pruner.prune();
        assertThat(sessionRepository.findById(sid)).isEmpty();
        flow.assertRejected(tokens.accessToken());
    }

    @Test
    void shouldRefuseTheRefreshWhenItWaitsBehindCleanupDeletingItsExpiredSession() throws Exception {
        ClientSignInFlow.Tokens tokens = flow.signIn("race-cleanup-b");
        UUID sid = sidOf(tokens.accessToken());
        expire(sid);

        Outcome<Boolean, Optional<ClientSessionService.ClientTokens>> outcome = inOrder(
                () -> {
                    pruner.prune();
                    return true;
                },
                () -> clientSessions.refresh(tokens.refreshToken(), null));

        assertThat(outcome.second()).isEmpty();
        assertThat(sessionRepository.findById(sid)).isEmpty();
        flow.assertRejected(tokens.accessToken());
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────────

    record Outcome<A, B>(A first, B second) {}

    /**
     * Runs {@code first} to completion in a transaction held open, starts {@code second} on another
     * thread, waits until PostgreSQL reports a backend blocked on a lock, then commits {@code first}.
     */
    private <A, B> Outcome<A, B> inOrder(Supplier<A> first, Supplier<B> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<A> a = pool.submit(() -> tx.execute(status -> {
                try {
                    return first.get();
                } finally {
                    firstDone.countDown();
                    await(release);
                }
            }));
            assertThat(firstDone.await(20, TimeUnit.SECONDS))
                    .as("first operation completed")
                    .isTrue();
            if (a.isDone()) {
                a.get(); // surfaces the first operation's failure
            }
            Future<B> b = pool.submit(() -> tx.execute(status -> second.get()));
            awaitLockWaiters(1, b);
            release.countDown();
            A firstResult = Objects.requireNonNull(a.get(20, TimeUnit.SECONDS));
            B secondResult = Objects.requireNonNull(b.get(20, TimeUnit.SECONDS));
            return new Outcome<>(firstResult, secondResult);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Waits until PostgreSQL reports {@code waiters} backends blocked on a lock; {@code watched} must not finish first. */
    private void awaitLockWaiters(int waiters, Future<?> watched) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (watched.isDone()) {
                watched.get();
                fail("the operation finished without waiting for the locks held before it");
            }
            Long waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Long.class);
            if (waiting != null && waiting >= waiters) {
                return;
            }
            Thread.sleep(20);
        }
        fail("the second operation never blocked on a lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** After a revocation commits: the access token no longer authenticates and the secret no longer refreshes. */
    private void assertFamilyDead(String accessToken, String refreshToken) {
        flow.assertRejected(accessToken);
        flow.assertRefreshRefused(refreshToken);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM issued_jwt WHERE session_id = ? AND revoked_at IS NULL",
                        Long.class,
                        sidOf(accessToken)))
                .isZero();
    }

    private Optional<String> createHandoff(Long accountId, String verifier) {
        Instant now = Instant.now();
        InstalledClient client = registry.find(CLIENT_ID, CALLBACK).orElseThrow();
        return clientSessions.createHandoff(
                accountId, client, ClientSignInFlow.challengeOf(verifier), now.plus(Duration.ofDays(7)), now);
    }

    private void expire(UUID sid) {
        jdbc.update(
                "UPDATE client_session SET session_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                sid);
    }

    private Long admin(String username) {
        webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "admin", true))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        return accountIdOf(username);
    }

    private String webLogin(String username) {
        ResponseCookie cookie = webTestClient
                .post()
                .uri("/auth/dev-login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "admin", false))
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class)
                .getResponseCookies()
                .getFirst(cookieName);
        return Objects.requireNonNull(cookie).getValue();
    }

    private static TokenConstraints constraintsOf(Jwt token) {
        Object sessionExp = token.getClaim("session_exp");
        Object authTime = token.getClaim("auth_time");
        return TokenConstraints.session(
                Instant.ofEpochSecond(((Number) Objects.requireNonNull(sessionExp)).longValue()),
                Instant.ofEpochSecond(((Number) Objects.requireNonNull(authTime)).longValue()));
    }

    private Long accountIdOf(String username) {
        return Objects.requireNonNull(accountRepository
                .findByPrimaryEmail(username + "@dev.invalid")
                .orElseThrow()
                .getId());
    }

    private Long accountOf(ClientSignInFlow.Tokens tokens) {
        return ClientSignInFlow.accountId(tokens.accessToken());
    }

    private UUID sidOf(String accessToken) {
        return ClientSignInFlow.sid(accessToken);
    }

    private UUID jtiOf(String accessToken) {
        return ClientSignInFlow.jti(accessToken);
    }
}
