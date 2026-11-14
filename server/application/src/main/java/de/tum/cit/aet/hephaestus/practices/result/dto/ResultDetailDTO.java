package de.tum.cit.aet.hephaestus.practices.result.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.OwnFeedbackRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.UnverifiableReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResultDetailDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResultOutcome outcome,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Severity severity,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String summary,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewOrigin origin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt,
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

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$") @Nullable
        String practiceVersionId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Integer practiceVersionNumber,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String itemVersion,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String explanation,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ResultEvidenceDTO evidence,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProblemDTO> problems,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ResultExampleDTO example,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ResultCoverage coverage,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant endedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ResultEndReason endReason,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OwnFeedbackRefDTO> feedback) {
    public ResultDetailDTO {
        problems = List.copyOf(problems);
        feedback = List.copyOf(feedback);
    }
}
