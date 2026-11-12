package de.tum.cit.aet.hephaestus.mentor;

import java.util.UUID;

/**
 * SQL for a test that writes turn rows itself: the run a turn's status needs, and the statements that move a turn and
 * its run together. The database checks when each transaction commits that a turn and its run agree.
 */
public final class MentorTurnRunRows {

    private MentorTurnRunRows() {}

    /** Inserts the run of a turn that is {@code turnStatus}, under the claim {@code attempt} when it ran. */
    public static String insert(UUID runId, long workspaceId, UUID conversationId, String turnStatus, int attempt) {
        String state = switch (turnStatus) {
            case "QUEUED" -> "QUEUED";
            case "RUNNING" -> "LEASED";
            default -> "ENDED";
        };
        return """
                INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                    leased_until, available_at, start_within_seconds, stop_reason, created_at, started_at, ended_at)
                VALUES ('%s', %d, 'TURN', 'INTERACTIVE', 'conversation:%s', '%s', 0, %d,
                    %s, now(), 180, %s, now(), %s, %s)
                """.formatted(
                        runId,
                        workspaceId,
                        conversationId,
                        state,
                        attempt,
                        state.equals("LEASED") ? "now() + interval '5 minutes'" : "NULL",
                        state.equals("ENDED") ? "'COMPLETED'" : "NULL",
                        state.equals("QUEUED") ? "NULL" : "now()",
                        state.equals("ENDED") ? "now()" : "NULL");
    }

    /** Ends the turn {@code DONE} and its run, in one statement. */
    public static String endDone(UUID turnId) {
        return """
                WITH ended AS (
                    UPDATE agent_run SET state = 'ENDED', leased_until = NULL, deadline = NULL, stop_reason = 'COMPLETED',
                                         ended_at = now()
                     WHERE id = (SELECT run_id FROM mentor_turn WHERE id = '%1$s') AND state <> 'ENDED')
                UPDATE mentor_turn SET status = 'DONE', finished_at = now() WHERE id = '%1$s'
                """.formatted(turnId);
    }

    /** Starts the running turn's next attempt, as a take-back and the next claim of its run do, in one block. */
    public static String retry(UUID turnId) {
        return """
                DO $$
                DECLARE run uuid := (SELECT run_id FROM mentor_turn WHERE id = '%1$s');
                BEGIN
                 UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, deadline = NULL, attempt = attempt + 1
                  WHERE id = run;
                 UPDATE agent_run SET state = 'LEASED', lease_epoch = lease_epoch + 1,
                                      leased_until = now() + interval '5 minutes'
                  WHERE id = run;
                 UPDATE mentor_turn SET attempt = attempt + 1, started_at = clock_timestamp() WHERE id = '%1$s';
                END $$
                """.formatted(turnId);
    }
}
