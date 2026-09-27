package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Turns practice reviews and their triggers on or off.
 * All fields are nullable — {@code null} means "no change" (PATCH semantics).
 */
@Schema(description = "Request to turn practice reviews and their triggers on or off. Null fields are left unchanged.")
public record UpdateWorkspaceFeaturesRequestDTO(
        @Schema(description = "Enable the practice review feature") @Nullable
        Boolean practicesEnabled,

        @Schema(description = "Enable automatic practice reviews triggered by PR events") @Nullable
        Boolean practiceReviewAutoTriggerEnabled,

        @Schema(description = "Enable manual practice reviews triggered via bot command") @Nullable
        Boolean practiceReviewManualTriggerEnabled) {}
