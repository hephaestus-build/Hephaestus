package de.tum.cit.aet.hephaestus.notification.preferences;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

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
        Boolean surveySummaries) {}
