package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The planned pairs of one practice, or of one practice level, by what became of them. Each planned pair is reviewed,
 * withheld or not reviewed. A reused pair is reviewed too, so {@code reused} is a part of {@code reviewed}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewItemCountsDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int listed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int reviewed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int reused,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int withheld,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int notReviewed) {
    /** Both counts together, for planned pairs of one level only. */
    public ReviewItemCountsDTO plus(ReviewItemCountsDTO other) {
        return new ReviewItemCountsDTO(
                listed + other.listed,
                reviewed + other.reviewed,
                reused + other.reused,
                withheld + other.withheld,
                notReviewed + other.notReviewed);
    }
}
