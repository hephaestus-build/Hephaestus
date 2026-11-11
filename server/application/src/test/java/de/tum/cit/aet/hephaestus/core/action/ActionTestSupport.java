package de.tum.cit.aet.hephaestus.core.action;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

/** Runs the outbox in a test. */
public final class ActionTestSupport {
    private ActionTestSupport() {}

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
}
