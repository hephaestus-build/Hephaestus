package de.tum.cit.aet.hephaestus.practices.feedback;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conversation feedback for a developer, written as rows: the feedback, its review and item, and its conversation copy
 * as the claim of a running Hephaestus turn leaves it. The claim's own rules are tested in
 * {@code FeedbackConversationReceiptIntegrationTest}; a test of what settles a claim starts here.
 */
public final class ConversationFeedbackRows {

    private ConversationFeedbackRows() {}

    /**
     * Feedback the turn's running attempt claimed: the feedback is UNCONFIRMED and its CHAT_TURN copy is written by the
     * turn, on the database clock, after the attempt started.
     *
     * @param developerAccountId a member of the workspace, the owner of the turn's conversation
     * @return the feedback id
     */
    public static UUID claimedBy(
            JdbcTemplate jdbc, long workspaceId, long developerAccountId, UUID turnId, String body) {
        Long item = jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,'scm.pull_request',"
                        + "(SELECT COALESCE(max(source_id),0)+1 FROM item),now()) RETURNING id",
                Long.class,
                workspaceId);
        UUID review = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at)"
                        + " VALUES (?,?,?,'ITEM','v1','REQUEST','RUNNING',now(),now())",
                review,
                workspaceId,
                item);
        UUID feedback = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,stop_reason,body,"
                        + "created_at) VALUES (?,?,?,?,?,'CONVERSATION','UNCONFIRMED','DELIVERY_UNCONFIRMED',?,now())",
                feedback,
                workspaceId,
                review,
                item,
                developerAccountId,
                body);
        UUID copy = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,state,write_key,write_started_at,"
                        + "conversation_turn_id,stop_reason,readback,created_at) VALUES (?,?,?,'CHAT_TURN',0,'WRITE_STARTED',?,"
                        + "now(),?,'DELIVERY_UNCONFIRMED','UNKNOWN',now())",
                copy,
                workspaceId,
                feedback,
                copy.toString(),
                turnId);
        return feedback;
    }

    /** The feedback's state, and its conversation copy's state and the Hephaestus answer the copy names. */
    public static String settled(JdbcTemplate jdbc, UUID feedbackId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT f.state||'/'||c.state||'/'||COALESCE(CAST(c.conversation_entry_id AS text),'-') FROM feedback f"
                        + " JOIN feedback_copy c ON c.feedback_id=f.id AND c.workspace_id=f.workspace_id WHERE f.id=?",
                String.class,
                feedbackId));
    }
}
