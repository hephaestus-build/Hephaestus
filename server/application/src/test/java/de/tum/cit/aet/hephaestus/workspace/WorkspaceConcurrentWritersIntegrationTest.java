package de.tum.cit.aet.hephaestus.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each test holds a first writer's transaction open until PostgreSQL shows the second writer waiting on a lock,
 * then commits it, so the interleaving is the same on every run.
 */
@DisplayName("Concurrent writers over PostgreSQL")
class WorkspaceConcurrentWritersIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void shouldKeepThePurgeWhenASuspendRacesIt() throws Exception {
        String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = Objects.requireNonNull(workspaceRepository
                .saveAndFlush(WorkspaceTestFixtures.activeWorkspace(slug))
                .getId());

        Future<Throwable> suspend = whileFirstWriterHoldsItsTransaction(
                "workspace", () -> lifecycle.purgeWorkspace(slug), () -> lifecycle.suspendWorkspace(slug));

        assertThat(suspend.get(30, SECONDS)).isInstanceOf(WorkspaceLifecycleViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspaceId))
                .isEqualTo("PURGED");
    }

    @Test
    void shouldReturnTheSameOrganizationWhenTwoWritersCreateItTogether() throws Exception {
        long providerId = Objects.requireNonNull(ensureGitHubProvider().getId());
        long nativeId = 7_000_000_000L + (UUID.randomUUID().getMostSignificantBits() & 0xFFFFFF);
        long[] firstId = new long[1];

        Future<Throwable> second = whileFirstWriterHoldsItsTransaction(
                "organization",
                () -> firstId[0] = Objects.requireNonNull(organizations
                        .upsertIdentity(nativeId, "first-login", providerId)
                        .getId()),
                () -> organizations.upsertIdentity(nativeId, "second-login", providerId));

        assertThat(second.get(30, SECONDS)).isNull();
        assertThat(jdbc.queryForList(
                        "SELECT id FROM organization WHERE native_id = ? AND provider_id = ?",
                        Long.class,
                        nativeId,
                        providerId))
                .containsExactly(firstId[0]);
        assertThat(jdbc.queryForObject("SELECT login FROM organization WHERE id = ?", String.class, firstId[0]))
                .isEqualTo("second-login");
    }

    /** Runs {@code first} in a transaction that commits only after {@code second} waits on a lock; returns its failure. */
    private Future<Throwable> whileFirstWriterHoldsItsTransaction(String table, Runnable first, Runnable second)
            throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> firstWriter = threads.submit(() -> transactions.executeWithoutResult(status -> {
            first.run();
            written.countDown();
            await(release);
        }));
        try {
            assertThat(written.await(30, SECONDS)).isTrue();
            Callable<Throwable> secondWriter = () -> {
                try {
                    second.run();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            Future<Throwable> outcome = threads.submit(secondWriter);
            awaitALockWaitOn(table);
            release.countDown();
            firstWriter.get(30, SECONDS);
            return outcome;
        } finally {
            release.countDown();
        }
    }

    private void awaitALockWaitOn(String table) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND datname = current_database() AND query ILIKE ?",
                    Integer.class,
                    "%" + table + "%");
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("The second writer never waited on the first writer's lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, SECONDS)) throw new AssertionError("The test never released the first writer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
