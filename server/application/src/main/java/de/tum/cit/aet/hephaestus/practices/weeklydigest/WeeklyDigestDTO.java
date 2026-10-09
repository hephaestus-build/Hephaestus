package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.practices.profile.dto.ProfileChangeDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * A weekly digest as it reads now. Feedback that its surface would hide no longer shows, and the count of unread
 * cards is the one the Practice profile shows. Facts only: the app writes the sentences.
 */
@Schema(description = "A weekly digest as it reads now, from the live facts")
public record WeeklyDigestDTO(
        @NonNull @Schema(description = "The Monday the week starts on, in the developer's time zone")
        LocalDate weekStart,

        @NonNull @Schema(description = "Monday 00:00 of the week in the developer's time zone")
        Instant windowStart,

        @NonNull @Schema(description = "The next Monday 00:00 in the developer's time zone")
        Instant windowEnd,

        @NonNull @Schema(description = "The IANA time zone the week was read in")
        String timeZone,

        @NonNull
        @Schema(description = "Feedback that reached the developer in the week, by practice group and practice order")
        List<WeeklyDigestFeedbackDTO> feedback,

        @NonNull
        @Schema(
                description = "What changed on the Practice profile in the week; never new feedback, which the"
                        + " feedback list covers")
        List<ProfileChangeDTO> changes,

        @Schema(
                requiredMode = RequiredMode.REQUIRED,
                description = "Pieces of feedback that wait unread on the Practice profile now")
        int waiting) {}
