package de.tum.cit.aet.hephaestus.core.action;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the outbox in a test. */
public final class ActionTestSupport {
    /** How long an attempt that the scheduled executor claimed may run before drain fails instead of waiting. */
    private static final Duration IN_FLIGHT_LIMIT = Duration.ofSeconds(30);

    private ActionTestSupport() {}

    /**
     * Makes every waiting action due, as if its backoff had passed, and runs the executor until no action is due and
     * none is executing. Earlier tests may have left due actions, so one run may not reach the caller's. The scheduled
     * executor also runs in an integration context and can claim the caller's action first: drain then waits for that
     * attempt to end, because an action it holds is neither due here nor finished. An expired lease no longer runs.
     */
    public static void drain(ActionExecutor executor, JdbcTemplate jdbc) {
        jdbc.update("UPDATE agent_action SET next_attempt_at = now() WHERE state = 'ADMITTED'");
        Instant limit = Instant.now().plus(IN_FLIGHT_LIMIT);
        for (int run = 0; run < 100; ) {
            Long due = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_action WHERE state = 'ADMITTED' AND next_attempt_at <= now()",
                    Long.class);
            if (due != null && due > 0) {
                executor.runDue();
                run++;
                continue;
            }
            Long executing = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_action WHERE state = 'EXECUTING' AND leased_until > now()", Long.class);
            if (executing == null || executing == 0) return;
            if (Instant.now().isAfter(limit))
                throw new IllegalStateException(executing + " outbox actions are still executing after drain");
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
    }
}
