package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.*;
import de.tum.cit.aet.hephaestus.testconfig.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NativePersonDataWriteFenceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private PersonSuppressionService suppression;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldFinishAnAdmittedWriteBeforeErasureChecksItsRows() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://fence.example.test"));
        var user = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var eraserPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(fence.holdForWrite(identities)).isTrue();
                admitted.countDown();
                waitFor(release);
                jdbc.update(
                        "INSERT INTO user_preferences(user_id,participate_in_research,ai_review_enabled) VALUES (?,false,false)",
                        user.getId());
            }));
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            var eraser = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                eraserPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                fence.holdForErasure(identities);
                return jdbc.queryForObject(
                        "SELECT count(*) FROM user_preferences WHERE user_id=?", Long.class, user.getId());
            }));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waitingForLock(eraserPid.get()));
                assertThat(eraser.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(eraser.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldRefuseAWaitingWriterAfterTheErasureControlCommitsWithoutMatchingAnotherSlackTeam() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        var identity = new PersonIdentity(Objects.requireNonNull(provider.getId()), "UFENCE", "T1");
        var identities = List.of(identity);
        var request = requests.saveAndFlush(new PersonDataRequest());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var eraser = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                fence.holdForErasure(identities);
                locked.countDown();
                waitFor(release);
                suppression.suppress(new PersonScope(null, identities, List.of()), request.getId());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                writerPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                return fence.holdForWrite(identities);
            }));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waitingForLock(writerPid.get()));
                assertThat(writer.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            eraser.get(10, TimeUnit.SECONDS);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isFalse();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> assertThat(fence.holdForWrite(
                                    List.of(new PersonIdentity(identity.providerId(), identity.subject(), "T2"))))
                            .isTrue());

        } finally {
            release.countDown();
        }
    }

    private boolean waitingForLock(int pid) {
        return pid != 0
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event='advisory')",
                        Boolean.class,
                        pid));
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
