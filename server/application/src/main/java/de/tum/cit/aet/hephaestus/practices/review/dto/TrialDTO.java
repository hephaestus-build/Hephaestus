package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A trial of one practice version on one item (ADR 0118 decision 8).
 *
 * @param currentVersion whether the tried version is the practice's current one; a tried version beside it is not
 * @param withheldReason why the practice ended without an outcome, when it did
 * @param outcome what the practice found, once admission accepted it
 * @param outcomeRemoved whether the trial's report was cleared, after its retention or by person erasure
 */
public record TrialDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus status,
        @Nullable ReviewStopReason stopReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String practiceSlug,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
        String practiceVersionId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int practiceVersionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PracticeState practiceState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean currentVersion,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string")
        String itemId,

        @Nullable String itemVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Nullable Instant completedAt,
        @Nullable String withheldReason,
        @Nullable TrialOutcomeDTO outcome,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean outcomeRemoved) {}
