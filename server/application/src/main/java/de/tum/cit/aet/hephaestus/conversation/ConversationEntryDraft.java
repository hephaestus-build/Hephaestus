package de.tum.cit.aet.hephaestus.conversation;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What a caller appends to a conversation; the ledger assigns the id, the sequence number and the time.
 *
 * @param searchText the words the app shows ({@link ConversationEntry#getSearchText}): the text of a message, nothing for
 *     any other entry; a Hephaestus message also has the words of its successful link calls ({@link ConversationPiEntries})
 * @param basisId the basis of the attempt that wrote the entry ({@link ConversationEntry#getBasisId}); {@code null} for a
 *     question or a digest
 */
public record ConversationEntryDraft(
        ConversationEntry.Kind kind,
        ConversationEntry.Surface surface,
        String text,
        String searchText,
        @Nullable JsonNode payload,
        @Nullable UUID turnId,
        @Nullable String requestId,
        @Nullable String slackTs,
        @Nullable UUID basisId) {

    public ConversationEntryDraft(
            ConversationEntry.Kind kind,
            ConversationEntry.Surface surface,
            String text,
            String searchText,
            @Nullable JsonNode payload,
            @Nullable UUID turnId,
            @Nullable String requestId,
            @Nullable String slackTs) {
        this(kind, surface, text, searchText, payload, turnId, requestId, slackTs, null);
    }

    /** A draft whose shown words are its text: a message without link calls, or no message at all. */
    public ConversationEntryDraft(
            ConversationEntry.Kind kind,
            ConversationEntry.Surface surface,
            String text,
            @Nullable JsonNode payload,
            @Nullable UUID turnId,
            @Nullable String requestId,
            @Nullable String slackTs) {
        this(kind, surface, text, kind.isMessage() ? text : "", payload, turnId, requestId, slackTs, null);
    }

    /** The same draft, written by the attempt whose basis this is. */
    public ConversationEntryDraft withBasis(UUID basis) {
        return new ConversationEntryDraft(kind, surface, text, searchText, payload, turnId, requestId, slackTs, basis);
    }

    /** The system's weekly digest stimulus, owned by the same turn that writes its model answer. */
    static ConversationEntryDraft digest(String text, JsonNode payload, String requestId, UUID turnId) {
        return new ConversationEntryDraft(
                ConversationEntry.Kind.DIGEST,
                ConversationEntry.Surface.SYSTEM,
                text,
                payload,
                turnId,
                requestId,
                null);
    }

    static ConversationEntryDraft question(
            ConversationEntry.Surface surface, String text, UUID turnId, String requestId, @Nullable String slackTs) {
        return new ConversationEntryDraft(ConversationEntry.Kind.USER, surface, text, null, turnId, requestId, slackTs);
    }
}
