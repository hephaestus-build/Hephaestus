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
public record ReviewDetailDTO(
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
                description =
                        "Active jobs that wait in the queue: for a worker, for sandbox room or for their next retry")
        int queuedJobCount,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String model,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewRuntimeDTO runtime,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewUsageDTO usage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ReviewStageTimingDTO> stageTiming,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        GeneratedPathReviewDTO generatedPaths) {
    public ReviewDetailDTO {
        practices = List.copyOf(practices);
        stageTiming = List.copyOf(stageTiming);
    }
}
