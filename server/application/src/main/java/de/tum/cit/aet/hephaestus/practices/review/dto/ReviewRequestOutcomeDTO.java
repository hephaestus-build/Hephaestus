package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ReviewRequestOutcomeDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewRequestStatus status,

        @Nullable UUID reviewId,

        @Nullable ReviewRequestReason reason) {}
