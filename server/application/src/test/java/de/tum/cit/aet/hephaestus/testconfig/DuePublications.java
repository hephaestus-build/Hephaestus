package de.tum.cit.aet.hephaestus.testconfig;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Moves the open publications of one event type past the retry backoff, as time would. The backoff counts from the last
 * attempt, so a test that retries a fresh failure at once must age both of its dates.
 */
public final class DuePublications {

    private DuePublications() {}

    public static void age(JdbcTemplate jdbc, Class<?> eventType) {
        jdbc.update("""
                UPDATE event_publication
                   SET publication_date = publication_date - interval '1 day',
                       last_resubmission_date = COALESCE(last_resubmission_date, publication_date) - interval '1 day'
                 WHERE completion_date IS NULL AND event_type = ?
                """, eventType.getName());
    }
}
