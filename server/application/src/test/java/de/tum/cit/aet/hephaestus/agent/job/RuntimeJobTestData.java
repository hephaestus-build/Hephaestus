package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHold;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class RuntimeJobTestData {
    /** How long a fixture's lease lasts unless the test sets another. */
    public static final Duration LEASE = Duration.ofMinutes(10);

    private RuntimeJobTestData() {}

    public static void initialize(ReviewJob job, long workspaceId) {
        if (job.getId() == null) job.setId(UUID.randomUUID());
        job.setWorkspaceId(workspaceId);
        if (job.getReviewId() == null) job.setReviewId(UUID.randomUUID());
        if (job.getRunId() == null) job.setRunId(UUID.randomUUID());
        if (job.getInput() == null)
            job.setInput(new ReviewJobInput(1, List.of(new ReviewAssignmentId(job.getReviewId(), 1, 1))));
    }

    /** Sets the attempt number that a load of the job reads from its run; for tests without a database. */
    public static ReviewJob attempt(ReviewJob job, int attempt) {
        ReflectionTestUtils.setField(job, "attempt", attempt);
        return job;
    }

    /** Sets the spent attempts that a load of the job reads from its run; for tests without a database. */
    public static ReviewJob spent(ReviewJob job, int spent) {
        ReflectionTestUtils.setField(job, "spentAttempts", spent);
        return job;
    }

    /** The fence of the claim that the job's run holds now. */
    public static AgentRunFence fence(JdbcTemplate jdbc, ReviewJob job) {
        Integer epoch =
                jdbc.queryForObject("SELECT lease_epoch FROM agent_run WHERE id=?", Integer.class, job.getRunId());
        return new AgentRunFence(job.getRunId(), job.getWorkspaceId(), Objects.requireNonNull(epoch));
    }

    /**
     * Inserts the job's review if it is missing, and the job's run in the state its status needs: a queued run for a
     * queued job, a run leased under {@code max(1, attempt)} for a running one, an ended run otherwise. Call it before
     * the job is saved; the database refuses a job and a run that disagree when the transaction commits.
     */
    public static ReviewJob persistParent(JdbcTemplate jdbc, ReviewJob job) {
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM review WHERE id=? AND workspace_id=?)",
                Boolean.class,
                job.getReviewId(),
                job.getWorkspaceId());
        if (!Boolean.TRUE.equals(present)) {
            long sourceId = (job.getReviewId().getLeastSignificantBits() & Long.MAX_VALUE) | 1;
            Long itemId = jdbc.queryForObject(
                    "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?, 'test.item', ?, now())"
                            + " RETURNING id",
                    Long.class,
                    job.getWorkspaceId(),
                    sourceId);
            if (itemId == null) throw new IllegalStateException("Fixture item was not inserted");
            jdbc.update(
                    "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,ordered_at) VALUES (?, ?, ?,"
                            + " 'ITEM', 'test:1', 'REQUEST', now())",
                    job.getReviewId(),
                    job.getWorkspaceId(),
                    itemId);
            job.setInput(new ReviewJobInput(itemId, List.of(new ReviewAssignmentId(job.getReviewId(), 1, itemId))));
        }
        persistRun(jdbc, job);
        return job;
    }

    /**
     * Inserts a run for a job that a test inserts with SQL, in the state that the job's status needs, and returns its
     * id for the job's {@code run_id}.
     *
     * @param attempt the job's attempt number
     */
    public static UUID insertRun(JdbcClient jdbc, long workspaceId, UUID reviewId, String jobStatus, int attempt) {
        UUID runId = UUID.randomUUID();
        String state = switch (jobStatus) {
            case "QUEUED" -> "QUEUED";
            case "RUNNING" -> "LEASED";
            default -> "ENDED";
        };
        jdbc.sql("""
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    leased_until, available_at, stop_reason, created_at, started_at, ended_at)
                VALUES (:id, :ws, 'REVIEW', 'BATCH', :subject, :state, 0, :epoch,
                    CASE WHEN :state = 'LEASED' THEN clock_timestamp() + interval '10 minutes' END, clock_timestamp(),
                    :reason, clock_timestamp(),
                    CASE WHEN :state <> 'QUEUED' THEN clock_timestamp() END,
                    CASE WHEN :state = 'ENDED' THEN clock_timestamp() END)
                """)
                .param("id", runId)
                .param("ws", workspaceId)
                .param("subject", ReviewRunKind.subject(reviewId) + ":" + runId)
                .param("state", state)
                .param(
                        "reason",
                        state.equals("ENDED")
                                ? ReviewJobTerminal.endReason(ReviewJobStatus.valueOf(jobStatus))
                                        .name()
                                : null)
                .param("epoch", state.equals("QUEUED") ? attempt : ReviewRunKind.epoch(attempt))
                .update();
        return runId;
    }

    public static UUID insertRun(JdbcTemplate jdbc, long workspaceId, UUID reviewId, String jobStatus, int attempt) {
        return insertRun(JdbcClient.create(jdbc), workspaceId, reviewId, jobStatus, attempt);
    }

    /** Inserts the job's run in the state its status needs, unless it exists; see {@link #persistParent}. */
    public static ReviewJob persistRun(JdbcTemplate jdbc, ReviewJob job) {
        if (job.getRunId() == null) job.setRunId(UUID.randomUUID());
        String state = switch (job.getStatus()) {
            case QUEUED -> "QUEUED";
            case RUNNING -> "LEASED";
            default -> "ENDED";
        };
        String current = runState(jdbc, job);
        if (current != null) {
            agree(jdbc, job, current, state);
            return job;
        }
        int epoch = state.equals("QUEUED") ? job.getAttempt() : ReviewRunKind.epoch(job.getAttempt());
        jdbc.update(
                """
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    leased_until, available_at, stop_reason, created_at, started_at, ended_at)
                VALUES (?, ?, 'REVIEW', 'BATCH', ?, ?, ?, ?,
                    CASE WHEN ? = 'LEASED' THEN clock_timestamp() + make_interval(secs => ?) END, clock_timestamp(),
                    ?, clock_timestamp(),
                    CASE WHEN ? <> 'QUEUED' THEN clock_timestamp() END,
                    CASE WHEN ? = 'ENDED' THEN clock_timestamp() END)
                """,
                job.getRunId(),
                job.getWorkspaceId(),
                ReviewRunKind.subject(job.getReviewId()) + ":" + job.getId(),
                state,
                job.spentAttempts(),
                epoch,
                state,
                LEASE.toSeconds(),
                state.equals("ENDED")
                        ? ReviewJobTerminal.endReason(job.getStatus()).name()
                        : null,
                state,
                state);
        return job;
    }

    /**
     * Moves the run of a job that a test wrote directly to the state that its stored status needs, in the caller's
     * transaction: production writes a job and its run together, and the database refuses a commit where they
     * disagree.
     */
    public static void agree(JdbcTemplate jdbc, UUID jobId) {
        var row = jdbc.queryForMap("SELECT status, run_id, workspace_id, review_id FROM review_job WHERE id=?", jobId);
        var job = new ReviewJob();
        job.setId(jobId);
        job.setRunId((UUID) Objects.requireNonNull(row.get("run_id")));
        job.setStatus(ReviewJobStatus.valueOf((String) Objects.requireNonNull(row.get("status"))));
        job.setWorkspaceId(((Number) Objects.requireNonNull(row.get("workspace_id"))).longValue());
        job.setReviewId((UUID) Objects.requireNonNull(row.get("review_id")));
        String state = switch (job.getStatus()) {
            case QUEUED -> "QUEUED";
            case RUNNING -> "LEASED";
            default -> "ENDED";
        };
        String current = runState(jdbc, job);
        if (current == null) return;
        agree(jdbc, job, current, state);
    }

    /**
     * Runs an {@code UPDATE review_job} that a test writes with SQL, and moves the run of each job it changed to agree,
     * in one transaction, as production writes a job and its run together.
     *
     * @return the jobs the update changed
     */
    public static List<UUID> updateJobs(JdbcTemplate jdbc, String update, Object... args) {
        return inTransaction(jdbc, () -> {
            List<UUID> changed = jdbc.query(update + " RETURNING id", (rs, row) -> rs.getObject(1, UUID.class), args);
            changed.forEach(jobId -> agree(jdbc, jobId));
            return changed;
        });
    }

    /**
     * Runs a test's own write of one job, for example through a {@code JdbcClient}, and moves the job's run to agree,
     * in one transaction.
     */
    public static void moveJob(JdbcTemplate jdbc, UUID jobId, Runnable update) {
        inTransaction(jdbc, () -> {
            update.run();
            agree(jdbc, jobId);
            return jobId;
        });
    }

    /**
     * Saves a stored job whose status a test changed, and moves its run to agree, in one transaction of the
     * application's transaction manager.
     *
     * @return the saved job
     */
    public static ReviewJob save(
            TransactionTemplate transactions, JdbcTemplate jdbc, ReviewJobRepository jobs, ReviewJob job) {
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewJob saved = jobs.saveAndFlush(job);
            persistRun(jdbc, saved);
            return saved;
        }));
    }

    /**
     * Runs {@code work} in one transaction, or in the caller's when one is open: the database checks that a job and its
     * run agree when the transaction commits, so the writes that move them together must share it.
     */
    private static <T> T inTransaction(JdbcTemplate jdbc, Supplier<T> work) {
        // A JPA transaction binds its connection without marking it active for a DataSourceTransactionManager, which
        // would then commit that connection itself: inside a transaction, the work joins it directly.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return work.get();
        var transactions =
                new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    /** Moves the job's existing run to {@code state}, as the transitions the job's status took would have. */
    private static void agree(JdbcTemplate jdbc, ReviewJob job, String current, String state) {
        if (current.equals("ENDED")) {
            String reason =
                    jdbc.queryForObject("SELECT stop_reason FROM agent_run WHERE id=?", String.class, job.getRunId());
            if (!state.equals("ENDED")
                    || !ReviewJobTerminal.endReason(job.getStatus()).name().equals(reason)) {
                // A fixture that changes an ended job uses a new run: the original run remains final.
                int nextAttempt = state.equals("ENDED") ? job.getAttempt() : 0;
                UUID next = insertRun(
                        jdbc,
                        job.getWorkspaceId(),
                        job.getReviewId(),
                        job.getStatus().name(),
                        nextAttempt);
                jdbc.update("UPDATE review_job SET run_id=? WHERE id=?", next, job.getId());
                job.setRunId(next);
                attempt(job, nextAttempt);
            }
            return;
        }
        if (current.equals(state)) return;
        switch (state) {
            case "QUEUED" ->
                jdbc.update("UPDATE agent_run SET state='QUEUED', leased_until=NULL WHERE id=?", job.getRunId());
            case "LEASED" -> {
                jdbc.update(
                        "UPDATE agent_run SET state='LEASED', lease_epoch=lease_epoch+1, hold_reason=NULL, started_at=clock_timestamp(),"
                                + " leased_until=clock_timestamp()+make_interval(secs => ?) WHERE id=?",
                        LEASE.toSeconds(),
                        job.getRunId());
                attempt(job, ReviewRunKind.attempt(fence(jdbc, job).epoch()));
            }
            default ->
                jdbc.update(
                        "UPDATE agent_run SET state='ENDED', leased_until=NULL, hold_reason=NULL, stop_reason=?,"
                                + " ended_at=clock_timestamp() WHERE id=?",
                        ReviewJobTerminal.endReason(job.getStatus()).name(),
                        job.getRunId());
        }
    }

    /** Hands the job's run to a new claim, as a requeue and the next claim do; the job keeps running under it. */
    public static int reclaim(JdbcTemplate jdbc, ReviewJob job) {
        return reclaim(jdbc, job, 0);
    }

    /**
     * Hands the job's run to a new claim after a requeue that spent a retry, as a lost attempt and the next claim do;
     * the job keeps running under it.
     */
    public static int retry(JdbcTemplate jdbc, ReviewJob job) {
        int attempt = reclaim(jdbc, job, 1);
        spent(job, job.spentAttempts() + 1);
        return attempt;
    }

    private static int reclaim(JdbcTemplate jdbc, ReviewJob job, int spends) {
        inTransaction(jdbc, () -> {
            jdbc.update(
                    "UPDATE agent_run SET state='QUEUED', leased_until=NULL, attempt=attempt+? WHERE id=?"
                            + " AND state='LEASED'",
                    spends,
                    job.getRunId());
            return jdbc.update(
                    "UPDATE agent_run SET state='LEASED', lease_epoch=lease_epoch+1,"
                            + " leased_until=clock_timestamp()+make_interval(secs => ?) WHERE id=?",
                    LEASE.toSeconds(),
                    job.getRunId());
        });
        int attempt = ReviewRunKind.attempt(fence(jdbc, job).epoch());
        attempt(job, attempt);
        return attempt;
    }

    /** Gives a queued job one more attempt behind it, as a claim and a requeue of its run do. */
    public static void passAttempt(JdbcTemplate jdbc, ReviewJob job) {
        inTransaction(jdbc, () -> {
            jdbc.update(
                    "UPDATE agent_run SET state='LEASED', lease_epoch=lease_epoch+1, hold_reason=NULL, started_at=clock_timestamp(),"
                            + " leased_until=clock_timestamp()+make_interval(secs => ?) WHERE id=? AND state='QUEUED'",
                    LEASE.toSeconds(),
                    job.getRunId());
            return jdbc.update("UPDATE agent_run SET state='QUEUED', leased_until=NULL WHERE id=?", job.getRunId());
        });
        attempt(job, job.getAttempt() + 1);
    }

    /** Moves the end of the job's lease; a time in the past lets it expire. */
    public static void leaseUntil(JdbcTemplate jdbc, ReviewJob job, Instant until) {
        jdbc.update(
                "UPDATE agent_run SET leased_until=? WHERE id=? AND state='LEASED'",
                Timestamp.from(until),
                job.getRunId());
    }

    /** Holds the job's queued run for {@code reason}, as a claim that finds no budget or no sandbox room does. */
    public static void hold(JdbcTemplate jdbc, ReviewJob job, AgentRunHold reason) {
        jdbc.update("UPDATE agent_run SET hold_reason=? WHERE id=? AND state='QUEUED'", reason.name(), job.getRunId());
    }

    /** The job's run state, or {@code null} when it has none. */
    public static @Nullable String runState(JdbcTemplate jdbc, ReviewJob job) {
        return jdbc
                .query("SELECT state FROM agent_run WHERE id=?", (rs, row) -> rs.getString(1), job.getRunId())
                .stream()
                .findFirst()
                .orElse(null);
    }
}
