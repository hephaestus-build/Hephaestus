package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The immutable version of a team item that the review pinned (ADR 0165), with its number in that item. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubmittedVersionDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "SUBMITTED_VERSION")
        String kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID versionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int number)
        implements ReviewedVersionDTO {
    public SubmittedVersionDTO {
        if (!"SUBMITTED_VERSION".equals(kind)) {
            throw new IllegalArgumentException("Invalid kind");
        }
    }
}
