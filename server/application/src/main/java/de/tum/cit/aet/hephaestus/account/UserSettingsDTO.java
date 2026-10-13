package de.tum.cit.aet.hephaestus.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "User preferences and settings")
public record UserSettingsDTO(
        @NonNull
        @NotNull(message = "practiceFeedbackDeliveryEnabled must not be null")
        @Schema(
                description =
                        "Whether new practice-feedback comments may be delivered on issues, pull requests, and merge requests authored by the user")
        Boolean practiceFeedbackDeliveryEnabled,

        @Nullable
        @Size(max = 64)
        @IanaTimeZone
        @Schema(
                description = "The account's IANA time zone, such as Europe/Berlin. The weekly digest follows the"
                        + " developer's week in it. Absent means UTC.")
        String timeZone) {}
