package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.StandingDisputeRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "A developer's standing dispute of feedback written for them. The explanation is theirs, written"
                + " to the workspace's administrators; the feedback's own text stays as private as its channel keeps"
                + " it.")
public record FeedbackDisputeDTO(
        @NonNull UUID feedbackId,
        @NonNull FeedbackChannel channel,

        @NonNull @Schema(description = "Why the developer thinks the feedback is wrong, in their words")
        String explanation,

        @NonNull Instant disputedAt) {
    public static FeedbackDisputeDTO from(StandingDisputeRow row) {
        return new FeedbackDisputeDTO(
                row.getFeedbackId(),
                FeedbackChannel.valueOf(row.getChannel()),
                row.getExplanation(),
                row.getDisputedAt());
    }
}
