package de.tum.cit.aet.hephaestus.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** A change to the user's settings. An absent time zone keeps the saved one. */
@Schema(description = "A change to the user's preferences and settings")
public record UpdateUserSettingsDTO(
        @NonNull
        @NotNull(message = "practiceFeedbackDeliveryEnabled must not be null")
        @Schema(
                description = "Whether new practice-feedback comments may be delivered on issues,"
                        + " pull requests, and merge requests authored by the user,"
                        + " together with related Slack reminders")
        Boolean practiceFeedbackDeliveryEnabled,

        @Nullable
        @Size(max = 64)
        @IanaTimeZone
        @Schema(
                description =
                        "The account's IANA time zone, such as Europe/Berlin. Absent keeps the saved time" + " zone.")
        String timeZone) {}
