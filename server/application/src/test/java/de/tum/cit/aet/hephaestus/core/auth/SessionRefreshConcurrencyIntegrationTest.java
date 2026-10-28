package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.AccessTokenAdmission;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionTemplate;

class SessionRefreshConcurrencyIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private AuthSessionService sessions;

    @Autowired
    private AccessTokenAdmission admission;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IssuedJwtRepository tokens;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtDecoder decoder;

    private static final TokenConstraints CONSTRAINTS =
            TokenConstraints.session(Instant.now().plusSeconds(3600), null);

    @Test
    void shouldKeepBothTokensUsableWhenFirstUseRollsBack() {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        UUID successor = unusedSuccessor(accountId, held.jti());

        tx.executeWithoutResult(status -> {
            assertThat(admission.admit(successor, Instant.now())).isNull();
            status.setRollbackOnly();
        });

        assertThat(admission.admit(held.jti(), Instant.now())).isNull();
        assertThat(tokens.findById(successor).orElseThrow().getPredecessorJti()).isEqualTo(held.jti());
        assertThat(admission.admit(successor, Instant.now())).isNull();
        assertThat(admission.admit(held.jti(), Instant.now())).isNotNull();
    }

    @Test
    void shouldLeaveOneUnusedSuccessorWhenTwoTabsRefreshTheSamePredecessor() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();

        inOrder(() -> refresh(accountId, held.jti()), () -> refresh(accountId, held.jti()));

        assertThat(tokens.findActiveByAccountId(accountId, Instant.now()))
                .filteredOn(row -> held.jti().equals(row.getPredecessorJti()))
                .hasSize(1);
        assertThat(admission.admit(held.jti(), Instant.now())).isNull();
    }

    @Test
    void shouldKeepTheSessionWhenConcurrentRefreshResponsesArriveInReverseOrder() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        var firstResponse = new MockHttpServletResponse();
        var secondResponse = new MockHttpServletResponse();

        inOrder(
                () -> sessions.refresh(accountId, held.jti(), CONSTRAINTS, new MockHttpServletRequest(), firstResponse),
                () -> sessions.refresh(
                        accountId, held.jti(), CONSTRAINTS, new MockHttpServletRequest(), secondResponse));

        // The second response arrives first; the late first response overwrites that cookie.
        String second = Objects.requireNonNull(secondResponse.getCookie("__Host-HEPHAESTUS_AT"))
                .getValue();
        String first = Objects.requireNonNull(firstResponse.getCookie("__Host-HEPHAESTUS_AT"))
                .getValue();
        assertThat(decoder.decode(second).getSubject()).isEqualTo(accountId.toString());
        assertThat(decoder.decode(first).getSubject()).isEqualTo(accountId.toString());
    }

    @Test
    void shouldWriteNoCookieWhenARefreshWaitsBehindTheSuccessorsFirstUse() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        UUID successor = unusedSuccessor(accountId, held.jti());
        var response = new MockHttpServletResponse();

        inOrder(
                () -> admission.admit(successor, Instant.now()) == null,
                () -> sessions.refresh(accountId, held.jti(), CONSTRAINTS, new MockHttpServletRequest(), response));

        assertThat(response.getCookies()).isEmpty();
        assertThat(admission.admit(held.jti(), Instant.now())).isNotNull();
        assertThat(admission.admit(successor, Instant.now())).isNull();
    }

    @Test
    void shouldAdmitTheSameSuccessorWhenAnotherRefreshHasReissuedIt() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        UUID successor = unusedSuccessor(accountId, held.jti());

        inOrder(() -> refresh(accountId, held.jti()), () -> admission.admit(successor, Instant.now()) == null);

        assertThat(admission.admit(successor, Instant.now())).isNull();
        assertThat(admission.admit(held.jti(), Instant.now())).isNotNull();
        assertThat(tokens.findActiveByAccountId(accountId, Instant.now()))
                .extracting(IssuedJwt::getJti)
                .containsExactly(successor);
    }

    @Test
    void shouldRefuseFirstUseWhenLogoutHasEndedThePredecessorAndItsUnusedSuccessor() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        UUID successor = unusedSuccessor(accountId, held.jti());

        inOrder(
                () -> {
                    sessions.logout(accountId, held.jti(), null, new MockHttpServletResponse());
                    return true;
                },
                () -> admission.admit(successor, Instant.now()) != null);

        assertThat(admission.admit(held.jti(), Instant.now())).isNotNull();
        assertThat(admission.admit(successor, Instant.now())).isNotNull();
    }

    @Test
    void shouldRefuseFirstUseWhenAccountWideRevocationCommitsFirst() throws Exception {
        var held = issue();
        Long accountId = tokens.findById(held.jti()).orElseThrow().getAccountId();
        UUID successor = unusedSuccessor(accountId, held.jti());

        inOrder(
                () -> {
                    sessions.revokeAllExcept(accountId, UUID.randomUUID(), null);
                    return true;
                },
                () -> admission.admit(successor, Instant.now()) != null);

        assertThat(admission.admit(successor, Instant.now())).isNotNull();
    }

    private HephaestusJwtIssuer.Token issue() {
        Long id = Objects.requireNonNull(
                accounts.save(new Account("Concurrent browser")).getId());
        return issuer.issue(id, CONSTRAINTS, null);
    }

    private boolean refresh(Long accountId, UUID jti) {
        return sessions.refresh(
                accountId, jti, CONSTRAINTS, new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    private UUID unusedSuccessor(Long accountId, UUID jti) {
        assertThat(refresh(accountId, jti)).isTrue();
        return tokens.findActiveByAccountId(accountId, Instant.now()).stream()
                .filter(row -> jti.equals(row.getPredecessorJti()))
                .map(IssuedJwt::getJti)
                .findFirst()
                .orElseThrow();
    }

    /** Holds the first transaction open until the second is blocked on its database locks. */
    private void inOrder(Supplier<Boolean> first, Supplier<Boolean> second) throws Exception {
        var firstDone = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> tx.execute(status -> {
                try {
                    return first.get();
                } finally {
                    firstDone.countDown();
                    await(release);
                }
            }));
            assertThat(firstDone.await(20, TimeUnit.SECONDS)).isTrue();
            var backend = new AtomicInteger();
            Future<Boolean> b = pool.submit(() -> tx.execute(status -> {
                backend.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                return second.get();
            }));
            awaitLockWaiter(b, backend);
            release.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitLockWaiter(Future<?> watched, AtomicInteger backend) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (watched.isDone()) {
                watched.get();
                fail("the second operation completed before the first transaction committed");
            }
            Long waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'",
                    Long.class,
                    backend.get());
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("the second operation never waited for the first transaction");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
