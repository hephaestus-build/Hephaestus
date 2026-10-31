package de.tum.cit.aet.hephaestus.notification.preferences;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.NonNull;

public record NotificationPreferencesDTO(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean productFeedback,

        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The account has an active verified email address")
        boolean emailAvailable,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description = "This instance has an email transport and sender configured")
        boolean deliveryConfigured,

        @Schema(requiredMode = RequiredMode.REQUIRED) boolean workspaceAlerts,

        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The weekly digest is on")
        boolean weeklyDigest,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description =
                        "Hephaestus can answer in at least one of your workspaces, so a weekly digest can be written")
        boolean weeklyDigestAvailable,

        @NonNull String etag) {}
