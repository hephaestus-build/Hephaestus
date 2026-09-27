package de.tum.cit.aet.hephaestus.agent.job;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(description = "Counts of feedback by delivery state")
public record ReviewFeedbackCountsDTO(
        @NonNull Long prepared,
        @NonNull Long delivered,
        @NonNull Long superseded,
        @NonNull Long suppressed,
        @NonNull Long failed,
        @NonNull Long unconfirmed) {
    public static ReviewFeedbackCountsDTO empty() {
        return new ReviewFeedbackCountsDTO(0L, 0L, 0L, 0L, 0L, 0L);
    }
}
