package de.tum.cit.aet.hephaestus.practices.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewProgressDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileReviewRefDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reviewId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewStatus status,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        ReviewProgressDTO progress) {}
