package de.tum.cit.aet.hephaestus.practices.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.dto.FeedbackDecisionDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDeveloperDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackLocation;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewFeedbackDetailDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewDeveloperDTO developer,
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
        Instant disagreedAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String body,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeedbackBasisDTO> basis,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeedbackCopyDTO> copies,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeedbackSendDTO> sends,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeedbackDecisionDTO> decisions,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The developer's current answer; absent when there is none, or only a rating")
        @Nullable
        FeedbackAnswerDTO answer,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeedbackCheckDTO> checks,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String contentDigest) {
    public ReviewFeedbackDetailDTO {
        basis = List.copyOf(basis);
        copies = List.copyOf(copies);
        sends = List.copyOf(sends);
        decisions = List.copyOf(decisions);
        checks = List.copyOf(checks);
    }
}
