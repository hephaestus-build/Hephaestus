package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.practices.observation.ObservationReferences;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Feedback that Heph showed in a message: the observation it is about, the words Heph wrote about it, and where the
 * developer can read about it.
 */
@Schema(description = "Feedback that Heph showed in a message")
public record MentorObservationLinkDTO(
        @NonNull UUID observationId,
        @NonNull String text,

        @Schema(description = "The practice the observation is about; absent when the observation is gone") @Nullable
        String practiceSlug,

        @Schema(description = "The practice's name; absent when the observation is gone") @Nullable
        String practiceName,

        @Schema(
                description = "The reviewed work the observation was seen on: the app links to the developer's"
                        + " feedback on it. Absent when the observation is gone")
        @Nullable
        ReviewedWorkRefDTO work) {

    static MentorObservationLinkDTO of(
            UUID observationId, String text, ObservationReferences.@Nullable Reference reference) {
        return reference == null
                ? new MentorObservationLinkDTO(observationId, text, null, null, null)
                : new MentorObservationLinkDTO(
                        observationId, text, reference.practiceSlug(), reference.practiceName(), reference.work());
    }
}
