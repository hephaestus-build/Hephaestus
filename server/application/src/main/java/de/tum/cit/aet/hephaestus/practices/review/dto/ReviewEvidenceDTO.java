package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One source of evidence from outside the reviewed version, pinned when the review was created, such as the CI runs of
 * a commit. Its slot names the source, for example {@code ci.observed}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewEvidenceDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slot,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewEvidenceState state,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant readAt) {}
