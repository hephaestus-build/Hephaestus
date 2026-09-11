package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * DTO for updating workspace feature flags.
 * All fields are nullable — {@code null} means "no change" (PATCH semantics).
 */
@Schema(description = "Request to update workspace feature flags. Null fields are left unchanged.")
public record UpdateWorkspaceFeaturesRequestDTO(
        @Schema(description = "Enable the practice review feature") @Nullable
        Boolean practicesEnabled,

        @Schema(description = "Enable the Pi mentor chat feature") @Nullable
        Boolean mentorEnabled,

        @Schema(description = "Enable the leaderboard ranking page") @Nullable
        Boolean leaderboardEnabled,

        @Schema(description = "Enable the league/progression system") @Nullable
        Boolean progressionEnabled,

        @Schema(description = "Enable league tiers and rankings") @Nullable
        Boolean leaguesEnabled,

        @Schema(description = "Enable automatic practice reviews triggered by PR events") @Nullable
        Boolean practiceReviewAutoTriggerEnabled,

        @Schema(description = "Enable manual practice reviews triggered via bot command") @Nullable
        Boolean practiceReviewManualTriggerEnabled) {}
