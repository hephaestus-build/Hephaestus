package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewSummaryDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewKind kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String itemVersion,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewOrigin origin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant completedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ItemEventDTO startedBy,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        AccountRefDTO requestedBy,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        UUID batchId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant orderedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewStopReason stopReason,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewPracticeDTO> practices,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewResultCountsDTO results,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewFeedbackCountsDTO feedback,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int jobCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int activeJobCount,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Active invocations that a worker runs now; the others wait for a worker")
        int runningJobCount,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String model) {
    public ReviewSummaryDTO {
        practices = List.copyOf(practices);
    }
}
