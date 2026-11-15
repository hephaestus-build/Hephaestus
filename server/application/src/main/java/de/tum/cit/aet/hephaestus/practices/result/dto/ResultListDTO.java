package de.tum.cit.aet.hephaestus.practices.result.dto;

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

public record ResultListDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResultOutcome outcome,

        @Nullable Severity severity,

        @Nullable String summary,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewOrigin origin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Currentness currentness,

        @Schema(
                description = "Why an unverifiable observation cannot be checked against the work; absent unless"
                        + " currentness is UNVERIFIABLE.")
        @Nullable
        UnverifiableReason unverifiableReason,

        @Schema(
                description = "When the observation's capture read the work: the work as of that time. A later check"
                        + " that found the work unchanged is not recorded. Absent when the capture record is missing.")
        @Nullable
        Instant capturedAt,

        @Nullable Instant markedIncorrectAt,

        @Nullable Instant acceptedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ProblemSummaryDTO> problems) {
    public ResultListDTO {
        problems = List.copyOf(problems);
    }
}
