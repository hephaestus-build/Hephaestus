package de.tum.cit.aet.hephaestus.notification.preferences;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

public record UpdateNotificationPreferencesDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean productFeedback,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean productSurveys,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean researchSurveys,
        @NotNull @NonNull NotificationEmailFrequency productFeedbackFrequency,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean workspaceAlerts,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean surveySummaries) {}
