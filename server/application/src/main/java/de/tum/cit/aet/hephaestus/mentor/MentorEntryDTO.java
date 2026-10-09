package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One message of a Heph conversation as the app shows it. Tool results, summaries and other internal entries are
 * never sent.
 */
@Schema(description = "One message of a Heph conversation")
public record MentorEntryDTO(
        @NonNull UUID id,
        @NonNull UUID conversationId,

        @Schema(description = "Set when the message is in a thread: the top-level message the thread replies to")
        @Nullable
        UUID parentEntryId,

        @Schema(description = "Order within its conversation", requiredMode = RequiredMode.REQUIRED)
        long seq,

        @NonNull Kind kind,

        @Schema(description = "Where the message was written; SYSTEM for a weekly digest")
        MentorEntry.@NonNull Surface surface,

        @Schema(
                description =
                        "The developer's words or Heph's answer; for a weekly digest, the note that points Heph to it; empty when the message was deleted")
        @NonNull
        String text,

        @Schema(description = "The submission that wrote the message; the app matches its own optimistic message by it")
        @Nullable
        String requestId,

        @Schema(description = "The turn the message asked or answered") @Nullable
        UUID turnId,

        @Schema(description = "The status of the turn this question asked; only on the developer's messages")
        MentorTurn.@Nullable Status turnStatus,

        @Schema(
                description =
                        "Why the turn this question asked failed, in words for the developer; only when it" + " FAILED")
        @Nullable
        String turnError,

        @NonNull Instant createdAt,

        @Schema(description = "Set when the developer edited the message in Slack") @Nullable
        Instant editedAt,

        @Schema(description = "The message was deleted; its text is gone", requiredMode = RequiredMode.REQUIRED)
        boolean deleted,

        @Schema(description = "The developer's vote; only on Heph's messages")
        MentorEntry.@Nullable Vote vote,

        @Schema(description = "Feedback Heph showed in this message, in order") @NonNull
        List<MentorObservationLinkDTO> observations,

        @Schema(description = "The thread on this message; only on top-level messages that have one") @Nullable
        MentorThreadSummaryDTO thread,

        @Schema(description = "The weekly digest as it reads now; only on a weekly digest that was not erased")
        @Nullable
        WeeklyDigestDTO digest) {

    /** Who wrote a message the app shows. */
    public enum Kind {
        USER,
        HEPH,
        DIGEST,
    }
}
