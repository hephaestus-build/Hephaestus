package de.tum.cit.aet.hephaestus.core.action;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Runs the outbox in a test. */
public final class ActionTestSupport {
    private ActionTestSupport() {}

    /** Uses the real executor and store with one test-owned provider adapter, including its end callback. */
    public static ActionExecutor withAdapter(ApplicationContext context, ActionAdapter replacement) {
        var adapters = Stream.concat(
                        context.getBeansOfType(ActionAdapter.class).values().stream()
                                .filter(adapter -> adapter.kind() != replacement.kind()),
                        Stream.of(replacement))
                .toList();
        return new ActionExecutor(
                context.getBean(ActionStore.class),
                context.getBean(ObjectMapper.class),
                adapters,
                new SimpleMeterRegistry());
    }

    /** Applies the real terminal-outcome retention policy. */
    public static void pruneEnded(ApplicationContext context, Duration age) {
        context.getBean(ActionStore.class).deleteEnded(age);
    }

    /**
     * Joins a scheduled batch, then runs due actions while holding the same test-only barrier. An EXECUTING row can
     * be an ended attempt waiting for readback, so database state does not say whether a Java call still runs.
     */
    public static void drain(ActionExecutor executor, JdbcTemplate jdbc) {
        Object target = AopTestUtils.getUltimateTargetObject(executor);
        synchronized (target) {
            jdbc.update("UPDATE agent_action SET next_attempt_at = now() WHERE state = 'ADMITTED'");
            for (int run = 0; run < 100; run++) {
                executor.runDue();
                Long due = jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE state = 'ADMITTED' AND next_attempt_at <= now()",
                        Long.class);
                if (due == null || due == 0) return;
            }
        }
    }

    /**
     * Makes a lane's head due now: an admitted action as if its backoff had passed, and an action that waits to read
     * back as if its wait had ended. A running attempt keeps its lease.
     */
    public static void due(JdbcTemplate jdbc, String laneKey) {
        jdbc.update(
                "UPDATE agent_action SET next_attempt_at = now() - interval '1 second'"
                        + " WHERE lane_key = ? AND state = 'ADMITTED'",
                laneKey);
        jdbc.update(
                "UPDATE agent_action SET leased_until = now() - interval '1 second'"
                        + " WHERE lane_key = ? AND state = 'EXECUTING' AND next_attempt_at > updated_at",
                laneKey);
    }

    /** Runs a due lane head, including lease recovery, with the executor's registered adapter. */
    public static Optional<ActionResult> runLane(ActionExecutor executor, JdbcTemplate jdbc, String laneKey) {
        return claim(jdbc, laneKey).map(executor::run);
    }

    /**
     * Claims the due head of one lane as the executor would, and carries it out with {@code adapter} in place of the
     * kind's own; the kind's own adapter still learns of an end. Empty when the lane has no due head.
     */
    public static Optional<ActionResult> runLane(
            ActionExecutor executor, JdbcTemplate jdbc, String laneKey, ActionAdapter adapter) {
        return claim(jdbc, laneKey).map(claim -> executor.run(claim, adapter));
    }

    /**
     * Claims the due head of one lane as the executor would, and returns the attempt without carrying it out, as if
     * the process that holds it stopped at any point. Its fence is the executor's.
     */
    public static Optional<ActionAttempt> claimLane(JdbcTemplate jdbc, ObjectMapper mapper, String laneKey) {
        return claim(jdbc, laneKey)
                .map(claim -> new ActionAttempt(
                        claim.id(),
                        claim.workspaceId(),
                        claim.kind(),
                        claim.idempotencyKey(),
                        mapper.readTree(claim.payload()),
                        claim.attempts(),
                        claim.epoch(),
                        claim.writeStarted(),
                        claim.readbackRef(),
                        () -> jdbc.update(
                                        "UPDATE agent_action SET write_started_at = COALESCE(write_started_at,"
                                                + " clock_timestamp()), updated_at = clock_timestamp()"
                                                + " WHERE id = ? AND lease_epoch = ? AND state = 'EXECUTING'"
                                                + " AND leased_until > clock_timestamp() + interval '60 seconds'",
                                        claim.id(),
                                        claim.epoch())
                                == 1));
    }

    /**
     * Records an attempt's result as the executor would, for a test that drove the attempt itself. Each adapter of the
     * kind learns of an end.
     */
    public static void record(ApplicationContext context, ActionAttempt attempt, ActionResult result) {
        var adapters = context.getBeansOfType(ActionAdapter.class).values();
        context.getBean(ActionStore.class).settle(attempt.id(), attempt.epoch(), result, ended -> {
            for (ActionAdapter adapter : adapters) if (adapter.kind() == ended.kind()) adapter.ended(ended);
        });
    }

    /** Ends one action that can no longer run, as the executor's reaper would; its kind's adapters learn of the end. */
    public static void endStale(ApplicationContext context, UUID id) {
        var adapters = context.getBeansOfType(ActionAdapter.class).values();
        context.getBean(ActionStore.class).endStale(id, ended -> {
            for (ActionAdapter adapter : adapters) if (adapter.kind() == ended.kind()) adapter.ended(ended);
        });
    }

    /** Ends the lease of a lane's running attempt, as if its process stopped. */
    public static void expireLease(JdbcTemplate jdbc, String laneKey) {
        jdbc.update(
                "UPDATE agent_action SET leased_until = now() - interval '1 second'"
                        + " WHERE lane_key = ? AND state = 'EXECUTING'",
                laneKey);
    }

    private static Optional<ActionStore.Claim> claim(JdbcTemplate jdbc, String laneKey) {
        var claims = jdbc.query(
                """
                UPDATE agent_action c
                   SET state = 'EXECUTING', lease_epoch = c.lease_epoch + 1, attempts = c.attempts + 1,
                       leased_until = clock_timestamp() + interval '2 minutes', updated_at = clock_timestamp()
                 WHERE c.id = (
                   SELECT d.id FROM agent_action d
                    WHERE d.lane_key = ?
                      AND ((d.state = 'ADMITTED' AND d.next_attempt_at <= clock_timestamp())
                        OR (d.state = 'EXECUTING' AND d.leased_until <= clock_timestamp()
                            AND d.attempts < d.max_attempts))
                      AND NOT EXISTS (SELECT 1 FROM agent_action b
                                       WHERE b.lane_key = d.lane_key AND b.id <> d.id AND (b.state = 'EXECUTING'
                                         OR (d.state = 'ADMITTED' AND b.id < d.id AND b.state IN ('PROPOSED', 'ADMITTED'))))
                    ORDER BY d.id LIMIT 1)
                RETURNING c.id, c.workspace_id, c.kind, c.idempotency_key, c.payload::text AS payload, c.attempts,
                          c.lease_epoch, c.write_started_at IS NOT NULL AS write_started, c.readback_ref
                """,
                (rs, row) -> new ActionStore.Claim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", Long.class),
                        ActionKind.valueOf(rs.getString("kind")),
                        rs.getString("idempotency_key"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getLong("lease_epoch"),
                        rs.getBoolean("write_started"),
                        rs.getString("readback_ref")),
                laneKey);
        return claims.stream().findFirst();
    }
}
