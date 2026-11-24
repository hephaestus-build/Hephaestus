package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPersonDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackLocation;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewFeedbackDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ItemRefDTO item,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Null for feedback on a practice page or in a conversation while the workspace"
                        + " has per-person results for admins off")
        @Nullable
        ReviewPersonDTO person,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackDestination destination,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackState state,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        FeedbackStopReason stopReason,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackLocation location,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        UUID replacesId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant deliveredAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String bodyPreview,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean bodyTruncated,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean textHidden,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean unverifiedHistory,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int resultCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FeedbackCopyCountsDTO copyCounts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean withdrawn,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        Instant disagreedAt) {}
