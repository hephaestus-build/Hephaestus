package de.tum.cit.aet.hephaestus.notification.preferences;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record UpdateNotificationPreferencesDTO(
        @NotNull @NonNull @Schema(requiredMode = RequiredMode.REQUIRED)
        Boolean productFeedback,

        @NotNull @NonNull @Schema(requiredMode = RequiredMode.REQUIRED)
        Boolean productSurveys,

        @NotNull @NonNull @Schema(requiredMode = RequiredMode.REQUIRED)
        Boolean researchSurveys,

        @NotNull @NonNull @Schema(requiredMode = RequiredMode.REQUIRED)
        Boolean workspaceAlerts,

        @NotNull @NonNull @Schema(requiredMode = RequiredMode.REQUIRED)
        Boolean surveySummaries,

        // Absent keeps the saved choice.
        @Nullable
        @Schema(
                description = "The weekly digest in your Heph conversation, with one note in Slack or email that it is"
                        + " ready. Absent keeps the saved choice.")
        Boolean weeklyDigest) {}
