package de.tum.cit.aet.hephaestus.mentor;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the ledger publishes inside the writing transaction. A listener that tells clients about a change listens
 * after commit, so nothing is shown before it is stored. The events carry ids and a turn's status and surface; a
 * reader loads the rows.
 */
public final class MentorLedgerEvents {

    private MentorLedgerEvents() {}

    /** One ledger change, as {@link MentorLedgerNotifications} carries it between processes. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TurnChanged.class, name = "turn"),
        @JsonSubTypes.Type(value = EntryChanged.class, name = "entry"),
        @JsonSubTypes.Type(value = ReadChanged.class, name = "read"),
    })
    public sealed interface LedgerChanged {
        long workspaceId();

        UUID conversationId();
    }

    /** Entries of one conversation were appended, or their text, vote or redaction changed. */
    public record EntryChanged(long workspaceId, UUID conversationId, List<UUID> entryIds) implements LedgerChanged {

        /** A notification payload stays below PostgreSQL's 8000-byte limit with this many entry ids. */
        static final int MAX_IDS = 100;

        /** The events for these entries of one conversation, as many as the notification limit needs. */
        static List<EntryChanged> of(long workspaceId, UUID conversationId, List<UUID> entryIds) {
            List<EntryChanged> changes = new ArrayList<>();
            for (int from = 0; from < entryIds.size(); from += MAX_IDS) {
                changes.add(new EntryChanged(
                        workspaceId,
                        conversationId,
                        List.copyOf(entryIds.subList(from, Math.min(entryIds.size(), from + MAX_IDS)))));
            }
            return changes;
        }
    }

    /**
     * The developer read their main conversation further: its read marker moved to {@code lastReadAt}.
     *
     * @param userId the developer the conversation is with, whose streams learn of it
     */
    public record ReadChanged(long workspaceId, UUID conversationId, long userId, Instant lastReadAt)
            implements LedgerChanged {}

    /**
     * A turn was submitted, claimed, stopped, ended or requeued, or its partial answer changed.
     *
     * @param attempt the turn's latest attempt, {@code 0} before its first claim; a requeued turn names the attempt
     *     that lost its lease
     */
    public record TurnChanged(
            long workspaceId,
            UUID conversationId,
            UUID turnId,
            MentorTurn.Status status,
            MentorTurn.Surface surface,
            int attempt)
            implements LedgerChanged {

        static TurnChanged of(MentorTurn turn) {
            return new TurnChanged(
                    turn.getWorkspaceId(),
                    turn.getConversationId(),
                    turn.getId(),
                    turn.getStatus(),
                    turn.getSurface(),
                    turn.getAttempt());
        }
    }
}
