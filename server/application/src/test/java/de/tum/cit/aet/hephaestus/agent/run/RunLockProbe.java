package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that a gateway route holds no lock that its run's lease renewal or the reaper waits on while the route does
 * its slow work (docs/contributor/agent-runs.mdx § Locks and slow work). The test makes the route's slow part call
 * {@link #hold()}. {@link #during} then renews the run's lease and locks each named row with a short lock timeout, and
 * only after that lets the route go on. A route whose transaction still holds the run's row, or a row that the kind's
 * {@code AgentRunHandler.lock} takes, fails the test instead of waiting.
 */
public final class RunLockProbe {

    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '1s'";
    private static final long WAIT_SECONDS = 10;

    private record RowLock(String table, String sql, List<Object> args) {}

    private final AgentRuns runs;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final AgentRunFence fence;
    private final List<RowLock> rows = new ArrayList<>();
    private final CountDownLatch held = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    private RunLockProbe(AgentRuns runs, TransactionTemplate transactions, JdbcTemplate jdbc, AgentRunFence fence) {
        this.runs = runs;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.fence = fence;
    }

    /** A probe of the run that {@code fence} holds. */
    public static RunLockProbe of(
            AgentRuns runs, TransactionTemplate transactions, JdbcTemplate jdbc, AgentRunFence fence) {
        return new RunLockProbe(runs, transactions, jdbc, fence);
    }

    /** Also locks the rows of {@code table} that {@code where} selects: a domain row the reaper locks first. */
    public RunLockProbe alsoLocks(String table, String where, Object... args) {
        rows.add(new RowLock(table, "SELECT 1 FROM " + table + " WHERE " + where + " FOR UPDATE", List.of(args)));
        return this;
    }

    /** Called from the route's slow part: holds the route there until the probe has renewed and locked. */
    public void hold() {
        held.countDown();
        try {
            assertThat(released.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the probe released the route")
                    .isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Runs {@code route}, checks the locks while it is held in its slow part, and answers what the route answered. */
    public <T> T during(Callable<T> route) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(route);
            assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the route reached its slow part")
                    .isTrue();
            try {
                Instant before = Objects.requireNonNull(
                        runs.find(fence.run()).orElseThrow().getLeasedUntil());
                Instant renewed = locked("agent_run", () -> runs.renew(fence)).orElseThrow();
                assertThat(renewed).as("the renewed lease").isAfter(before);
                for (RowLock row : rows) {
                    locked(
                            row.table(),
                            () -> jdbc.queryForList(row.sql(), row.args().toArray()));
                }
            } finally {
                released.countDown();
            }
            return running.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private <T> T locked(String table, Supplier<T> statement) {
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                jdbc.execute(LOCK_TIMEOUT);
                return statement.get();
            }));
        } catch (DataAccessException waited) {
            throw new AssertionError(
                    "The route holds a lock on " + table
                            + " while it works, so the run's renewal or reaper waits on it",
                    waited);
        }
    }
}
