package de.tum.cit.aet.hephaestus.testconfig;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Waits for the conversation receipts that a Hephaestus turn's outcome made due. The event publication registry runs them
 * after the outcome commits, on another thread; a failed run stays in the registry until it is resubmitted.
 */
public final class ConversationReceipts {

    private ConversationReceipts() {}

    /** Waits until no receipt publication is still to run: each one ran, or failed and waits for resubmission. */
    public static void awaitSettled(JdbcTemplate jdbc) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> pending(jdbc) == 0);
    }

    /** The receipt publications that failed and wait for resubmission. */
    public static int failed(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE listener_id LIKE '%ConversationTurnCompletion%'"
                        + " AND status = 'FAILED'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private static int pending(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE listener_id LIKE '%ConversationTurnCompletion%'"
                        + " AND completion_date IS NULL AND status IN ('PUBLISHED', 'PROCESSING', 'RESUBMITTED')",
                Integer.class);
        return count == null ? 0 : count;
    }
}
