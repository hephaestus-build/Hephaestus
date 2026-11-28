package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRunRows;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A completed Hephaestus answer that showed words for a piece of feedback through a successful {@code show_feedback}
 * call, and the posted conversation copy of the piece. The copy names the answer and holds the same words, as a receipt
 * that a server of the previous release posts while an upgrade rolls; readers take them from the answer. The rows are
 * written as the developer of the piece, in the main conversation of the developer.
 */
final class ShownConversationAnswers {
    /** The conversation, the completed turn, the answer that showed the words and its show_feedback result. */
    record Shown(UUID conversation, UUID turn, UUID answer, UUID result) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OwnerScope owners;

    ShownConversationAnswers(JdbcTemplate jdbc, PlatformTransactionManager manager, OwnerScope owners) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.owners = owners;
    }

    Shown show(Feedback piece, String words) {
        return Objects.requireNonNull(transaction.execute(status -> {
            long workspace = piece.getWorkspaceId();
            long developer = piece.getDeveloperId();
            owners.ofJob(developer);
            jdbc.update(
                    "INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                            + " VALUES (?,?,?,now(),now())"
                            + " ON CONFLICT (workspace_id,account_id) WHERE parent_conversation_id IS NULL DO NOTHING",
                    UUID.randomUUID(),
                    workspace,
                    developer);
            UUID conversation = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT id FROM conversation WHERE workspace_id=? AND account_id=? AND parent_conversation_id IS NULL",
                    UUID.class,
                    workspace,
                    developer));
            UUID turn = UUID.randomUUID();
            UUID run = UUID.randomUUID();
            jdbc.update(ConversationTurnRunRows.insert(run, workspace, conversation, "DONE", 1));
            jdbc.update(
                    "INSERT INTO conversation_turn(id,workspace_id,conversation_id,account_id,request_id,status,surface,"
                            + "attempt,run_id,created_at,finished_at) VALUES (?,?,?,?,?,'DONE','APP',1,?,now(),now())",
                    turn,
                    workspace,
                    conversation,
                    developer,
                    "request-" + turn,
                    run);
            UUID answer = UUID.randomUUID();
            String call = "call-" + piece.getId();
            entry(
                    answer,
                    piece,
                    conversation,
                    turn,
                    "ANSWER",
                    "Some context.",
                    ConversationLedgerFixtures.showFeedback(
                                    ConversationLedgerFixtures.piId(),
                                    "Some context.",
                                    Map.of(call, piece.getId()),
                                    words)
                            .toString());
            UUID result = UUID.randomUUID();
            entry(
                    result,
                    piece,
                    conversation,
                    turn,
                    "TOOL_RESULT",
                    "",
                    ConversationLedgerFixtures.toolResult(
                                    ConversationLedgerFixtures.piId(), call, "show_feedback", "Shown", false)
                            .toString());
            UUID copy = FeedbackIds.copy(piece.getId(), FeedbackCopyKind.CHAT_TURN, 0);
            var at = Timestamp.from(Objects.requireNonNullElse(piece.getDeliveredAt(), Instant.now()));
            jdbc.update(
                    "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,state,write_key,"
                            + "write_started_at,posted_at,conversation_turn_id,conversation_entry_id,created_at)"
                            + " VALUES (?,?,?,'CHAT_TURN',0,?,'POSTED',?,?,?,?,?,?)",
                    copy,
                    workspace,
                    piece.getId(),
                    words,
                    copy.toString(),
                    at,
                    at,
                    turn,
                    answer,
                    at);
            return new Shown(conversation, turn, answer, result);
        }));
    }

    /**
     * A posted receipt that stores words and names no answer. Without a write fence, it is a receipt that the 1.0 upgrade
     * brought from 0.x: changelog 1792397538973 dropped its answer. With one, it is a receipt of this release whose answer
     * an erasure deleted, which clears the binding and keeps the fence.
     */
    void unbound(Feedback piece, String words, @Nullable Instant writeStartedAt) {
        UUID copy = FeedbackIds.copy(piece.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        var at = Timestamp.from(Objects.requireNonNullElse(piece.getDeliveredAt(), Instant.now()));
        jdbc.update(
                "INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,body,state,write_key,"
                        + "write_started_at,created_at,posted_at) VALUES (?,?,?,'CHAT_TURN',0,?,'POSTED',?,?,?,?)",
                copy,
                piece.getWorkspaceId(),
                piece.getId(),
                words,
                writeStartedAt == null ? null : copy.toString(),
                writeStartedAt == null ? null : Timestamp.from(writeStartedAt),
                at,
                at);
    }

    private void entry(
            UUID id, Feedback piece, UUID conversation, UUID turn, String kind, String text, String payload) {
        jdbc.update(
                "INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,payload,"
                        + "turn_id,created_at) VALUES (?,?,?,?,"
                        + "(SELECT COALESCE(max(seq),0)+1 FROM conversation_entry WHERE conversation_id=?),"
                        + "?,'APP',?,?::jsonb,?,now())",
                id,
                piece.getWorkspaceId(),
                conversation,
                piece.getDeveloperId(),
                conversation,
                kind,
                text,
                payload,
                turn);
    }
}
