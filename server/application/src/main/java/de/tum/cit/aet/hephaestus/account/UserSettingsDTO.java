package de.tum.cit.aet.hephaestus.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

@Schema(description = "User preferences and settings")
public record UserSettingsDTO(
        @NonNull
        @NotNull(message = "practiceFeedbackDeliveryEnabled must not be null")
        @Schema(
                description =
                        "Whether new practice-feedback comments may be delivered on issues, pull requests, and merge requests authored by the user, together with related Slack reminders")
        Boolean practiceFeedbackDeliveryEnabled) {}
