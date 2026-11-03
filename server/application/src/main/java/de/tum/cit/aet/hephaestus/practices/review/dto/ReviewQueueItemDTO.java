package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewQueueItemDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID jobId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewJobStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean heldForBudget,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewDeveloperDTO developer,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant since,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewProgressDTO progress) {}
