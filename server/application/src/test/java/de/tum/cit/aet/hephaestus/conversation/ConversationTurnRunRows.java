package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunsAdapter;
import java.util.UUID;

/**
 * SQL for a test that writes turn rows itself: a turn with the run its status needs and its question, and the
 * statements that move a turn and its run together. The database checks when each transaction commits that a turn and its run agree.
 */
public final class ConversationTurnRunRows {

    private ConversationTurnRunRows() {}

    /**
     * Inserts a turn that is {@code turnStatus}, its run under the claim {@code attempt} when it ran, and the turn's
     * question at the conversation's next position, in one statement. The run stores the conversation's owner and the
     * question's position.
     */
    public static String insert(
            UUID turnId, UUID runId, long workspaceId, UUID conversationId, String turnStatus, int attempt) {
        String state = switch (turnStatus) {
            case "QUEUED" -> "QUEUED";
            case "RUNNING" -> "LEASED";
            default -> "ENDED";
        };
        return """
                DO $$
                DECLARE
                    conversation_owner bigint := (SELECT account_id FROM conversation WHERE id = '%3$s');
                    asked bigint := (SELECT COALESCE(max(seq), 0) + 1 FROM conversation_entry
                                     WHERE conversation_id = '%3$s');
                BEGIN
                    INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key, state, attempt, lease_epoch,
                        leased_until, available_at, start_within_seconds, stop_reason, created_at, started_at, ended_at,
                        account_id, conversation_id, subject_seq)
                    VALUES ('%1$s', %2$d, 'TURN', 'INTERACTIVE', 'conversation:%3$s', '%4$s', 0, %5$d,
                        %6$s, now(), 180, %7$s, now(), %8$s, %9$s, conversation_owner, '%3$s', asked);
                    INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id, status,
                        surface, attempt, run_id, created_at, started_at, finished_at)
                    VALUES ('%10$s', %2$d, '%3$s', conversation_owner, '%10$s', '%11$s', 'APP', %5$d, '%1$s', now(),
                        %12$s, %13$s);
                    INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface,
                        text, turn_id, created_at)
                    VALUES (gen_random_uuid(), %2$d, '%3$s', conversation_owner, asked, 'USER', 'APP', 'A question.',
                        '%10$s', now());
                END $$
                """.formatted(
                        runId,
                        workspaceId,
                        conversationId,
                        state,
                        attempt,
                        state.equals("LEASED") ? "now() + interval '5 minutes'" : "NULL",
                        state.equals("ENDED")
                                ? "'"
                                        + TurnRunsAdapter.endReason(ConversationTurn.Status.valueOf(turnStatus))
                                                .name() + "'"
                                : "NULL",
                        state.equals("QUEUED") ? "NULL" : "now()",
                        state.equals("ENDED") ? "now()" : "NULL",
                        turnId,
                        turnStatus,
                        turnStatus.equals("RUNNING") ? "now()" : "NULL",
                        state.equals("ENDED") ? "now()" : "NULL");
    }

    /** Ends the turn {@code DONE} and its run, in one statement. */
    public static String endDone(UUID turnId) {
        return """
                WITH ended AS (
                    UPDATE agent_run SET state = 'ENDED', leased_until = NULL, deadline = NULL, stop_reason = 'COMPLETED',
                                         ended_at = now()
                     WHERE id = (SELECT run_id FROM conversation_turn WHERE id = '%1$s') AND state <> 'ENDED')
                UPDATE conversation_turn SET status = 'DONE', finished_at = now() WHERE id = '%1$s'
                """.formatted(turnId);
    }

    /** Starts the running turn's next attempt, as a take-back and the next claim of its run do, in one block. */
    public static String retry(UUID turnId) {
        return """
                DO $$
                DECLARE run uuid := (SELECT run_id FROM conversation_turn WHERE id = '%1$s');
                BEGIN
                 UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, deadline = NULL, attempt = attempt + 1
                  WHERE id = run;
                 UPDATE agent_run SET state = 'LEASED', lease_epoch = lease_epoch + 1,
                                      leased_until = now() + interval '5 minutes'
                  WHERE id = run;
                 UPDATE conversation_turn SET attempt = attempt + 1, started_at = clock_timestamp() WHERE id = '%1$s';
                END $$
                """.formatted(turnId);
    }
}
