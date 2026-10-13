package de.tum.cit.aet.hephaestus.mentor;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** What a caller appends to a conversation; the ledger assigns the id, the sequence number and the time. */
public record MentorEntryDraft(
        MentorEntry.Kind kind,
        MentorEntry.Surface surface,
        String text,
        @Nullable JsonNode payload,
        @Nullable UUID turnId,
        @Nullable String requestId,
        @Nullable String slackTs) {

    /** A weekly digest: written by the server outside any turn, idempotent by {@code requestId}. */
    public static MentorEntryDraft digest(String text, JsonNode payload, String requestId) {
        return new MentorEntryDraft(
                MentorEntry.Kind.DIGEST, MentorEntry.Surface.SYSTEM, text, payload, null, requestId, null);
    }

    static MentorEntryDraft question(
            MentorEntry.Surface surface, String text, UUID turnId, String requestId, @Nullable String slackTs) {
        return new MentorEntryDraft(MentorEntry.Kind.USER, surface, text, null, turnId, requestId, slackTs);
    }
}
