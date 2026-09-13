package de.tum.cit.aet.hephaestus.notification.preferences;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.NonNull;

public record NotificationPreferencesDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean productFeedback,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean productSurveys,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean researchSurveys,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean emailAvailable,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean surveySummaries,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean workspaceAlerts,
        @NonNull NotificationEmailFrequency productFeedbackFrequency,
        @NonNull String etag) {}
