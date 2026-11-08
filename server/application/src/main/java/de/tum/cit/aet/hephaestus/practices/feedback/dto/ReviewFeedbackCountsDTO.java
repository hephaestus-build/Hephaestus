package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewFeedbackCountsDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int awaitingApproval,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ready,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int delivered,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int unconfirmed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int withheld,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int rejected,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int replaced,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int failed) {
    public static ReviewFeedbackCountsDTO from(Map<FeedbackState, Integer> counts) {
        return new ReviewFeedbackCountsDTO(
                counts.getOrDefault(FeedbackState.AWAITING_APPROVAL, 0),
                counts.getOrDefault(FeedbackState.READY, 0),
                counts.getOrDefault(FeedbackState.DELIVERED, 0),
                counts.getOrDefault(FeedbackState.UNCONFIRMED, 0),
                counts.getOrDefault(FeedbackState.WITHHELD, 0),
                counts.getOrDefault(FeedbackState.REJECTED, 0),
                counts.getOrDefault(FeedbackState.REPLACED, 0),
                counts.getOrDefault(FeedbackState.FAILED, 0));
    }
}
