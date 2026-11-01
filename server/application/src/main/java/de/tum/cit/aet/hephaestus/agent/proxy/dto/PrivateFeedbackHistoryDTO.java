package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The private feedback this developer received, and conversation feedback still queued for them, on each private
 * destination this composition may write for its practice. It is one bounded read, never the complete history:
 * {@code coverage} says what it leaves out. A body is present where its words may be copied. It records what was
 * said. {@code recordedClaimCurrentness} says separately whether the claim behind it still holds.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrivateFeedbackHistoryDTO(
        Instant readAt, Coverage coverage, List<Delivered> delivered, List<Queued> queued) {
    public PrivateFeedbackHistoryDTO {
        delivered = List.copyOf(delivered);
        queued = List.copyOf(queued);
    }

    /** What a body is: a practice-page card, the words Heph said in conversation, or notes queued for Heph. */
    public enum BodyRole {
        PRACTICE_PAGE_CARD,
        CONVERSATION_WORDS,
        MENTOR_NOTES
    }

    /**
     * @param hasMore older matching feedback exists beyond {@code maxEntries}
     * @param omittedWithoutCopyAuthority matching feedback left out because its support may not be copied here
     */
    public record Coverage(int maxEntries, boolean hasMore, int omittedWithoutCopyAuthority) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Delivered(
            UUID id,
            FeedbackDestination destination,
            Instant deliveredAt,
            Currentness recordedClaimCurrentness,
            boolean withdrawn,
            List<Basis> basedOn,
            @Nullable BodyRole bodyRole,
            @Nullable String body) {
        public Delivered {
            basedOn = List.copyOf(basedOn);
        }
    }

    /** Conversation feedback not yet delivered that a new piece on {@code practiceSlug} may replace. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Queued(
            String threadKey,
            UUID feedbackId,
            FeedbackDestination destination,
            String practiceSlug,
            Instant preparedAt,
            Currentness recordedClaimCurrentness,
            List<Basis> basedOn,
            @Nullable BodyRole bodyRole,
            @Nullable String body) {
        public Queued {
            basedOn = List.copyOf(basedOn);
        }
    }

    // Its own schema name: PublicFeedbackSnapshot.Basis carries a string version id, this one a number.
    @Schema(name = "PrivateFeedbackHistoryBasis")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Basis(
            UUID resultId, String practiceSlug, @Nullable Long practiceVersionId, ResultOutcome outcome) {}
}
