package de.tum.cit.aet.hephaestus.core.action;

import org.springframework.jdbc.core.JdbcTemplate;

/** Runs the outbox in a test, where no scheduler does. */
public final class ActionTestSupport {
    private ActionTestSupport() {}

    /**
     * Makes every waiting action due, as if its backoff had passed, and runs the executor until no action is due.
     * Earlier tests may have left due actions, so one run may not reach the caller's.
     */
    public static void drain(ActionExecutor executor, JdbcTemplate jdbc) {
        jdbc.update("UPDATE agent_action SET next_attempt_at = now() WHERE state = 'ADMITTED'");
        for (int run = 0; run < 100; run++) {
            Long due = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_action WHERE state = 'ADMITTED' AND next_attempt_at <= now()",
                    Long.class);
            if (due == null || due == 0) return;
            executor.runDue();
        }
    }
}
