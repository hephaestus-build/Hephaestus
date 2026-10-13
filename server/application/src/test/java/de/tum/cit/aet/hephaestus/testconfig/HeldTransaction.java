package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction that runs work on another thread and holds its locks until a second writer waits for it, so a test
 * drives two writers at once against PostgreSQL without a sleep.
 */
public final class HeldTransaction {

    private static final int PATIENCE_SECONDS = 20;

    private final int backendPid;
    private final CountDownLatch release;
    private final CompletableFuture<Void> done;
    private final JdbcTemplate jdbc;

    private HeldTransaction(int backendPid, CountDownLatch release, CompletableFuture<Void> done, JdbcTemplate jdbc) {
        this.backendPid = backendPid;
        this.release = release;
        this.done = done;
        this.jdbc = jdbc;
    }

    /** Runs {@code work} in a transaction on another thread and returns once it holds its locks. */
    public static HeldTransaction holdIn(TransactionTemplate transactions, JdbcTemplate jdbc, Runnable work)
            throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            work.run();
            pid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            holding.countDown();
            try {
                assertThat(release.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }));
        assertThat(holding.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();
        return new HeldTransaction(pid.get(), release, done, jdbc);
    }

    /** Commits once {@code other} waits for this transaction or has finished without it. */
    public void commitAfter(CompletableFuture<?> other) throws Exception {
        Awaitility.await().atMost(PATIENCE_SECONDS, TimeUnit.SECONDS).until(() -> {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            return other.isDone()
                    || Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                            Boolean.class,
                            backendPid));
        });
        release.countDown();
        done.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
    }
}
