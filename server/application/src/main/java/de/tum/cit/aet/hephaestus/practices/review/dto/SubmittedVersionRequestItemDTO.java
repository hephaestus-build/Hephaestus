package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A submitted version of a team item to review (ADR 0165). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubmittedVersionRequestItemDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "SUBMITTED_VERSION") @NotNull
        String kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        UUID versionId)
        implements ReviewRequestItemDTO {
    public SubmittedVersionRequestItemDTO {
        if (!"SUBMITTED_VERSION".equals(kind)) {
            throw new IllegalArgumentException("Invalid kind");
        }
    }
}
