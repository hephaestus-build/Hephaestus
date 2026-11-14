package de.tum.cit.aet.hephaestus.practices.reviewoutput.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.dto.GroupRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDeveloperDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewProblemDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID resultId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        GroupRefDTO group,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewDeveloperDTO developer,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Severity severity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String path,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Integer startLine,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Integer endLine,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        CitationSide side,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String sourceKind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean evidenceHidden,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currentness currentness,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Why an unverifiable observation cannot be checked against the work; null unless"
                        + " currentness is UNVERIFIABLE.")
        @Nullable
        UnverifiableReason unverifiableReason,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "When the observation's capture read the work: the work as of that time. A later check"
                        + " that found the work unchanged is not recorded. Null when the capture record is missing.")
        @Nullable
        Instant capturedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant markedIncorrectAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant acceptedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant disagreedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt) {}
