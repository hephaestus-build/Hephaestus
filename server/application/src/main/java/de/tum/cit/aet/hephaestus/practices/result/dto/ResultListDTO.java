package de.tum.cit.aet.hephaestus.practices.result.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
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
public record ResultListDTO(
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

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProblemSummaryDTO> problems) {
    public ResultListDTO {
        problems = List.copyOf(problems);
    }
}
