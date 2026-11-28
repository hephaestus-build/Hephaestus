package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * How a review differs from its baseline, the newest earlier closed review of the same setup and item (ADR 0167
 * decision 6). The three sides are independent: one commit with other pinned evidence is the same version with changed
 * evidence. Only the evidence can be unknown; the constructor refuses an unknown version or setup.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewChangeDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID baselineReviewId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewDifference version,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewDifference evidence,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewDifference setup) {
    public ReviewChangeDTO {
        if (version == ReviewDifference.UNKNOWN || setup == ReviewDifference.UNKNOWN) {
            throw new IllegalArgumentException("The version and the setup of two reviews are always known");
        }
    }
}
