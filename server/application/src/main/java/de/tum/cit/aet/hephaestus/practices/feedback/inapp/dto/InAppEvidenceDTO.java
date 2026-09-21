package de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One piece of work that carries the habit an in-app message is about.
 *
 * <p>The unit of proof at the process level is recurrence, so evidence here is a <em>set of
 * artifacts</em>, never a quoted line. A quoted line is task-level proof and it already appeared on the
 * work itself; repeating it would make the practice pages a second copy of the pull-request comment
 * instead of the thing the comment cannot say.
 *
 * <p>The work is named the way every surface names it, so the card can print "#22" and link it. The
 * outcome travels because the card marks each piece of work with what the review made of it, and it is a
 * category, not a level. No severity, no confidence: those are properties of the measurement, and a
 * surface that showed them would invite the reader to treat a count of them as a score.
 */
@Schema(description = "One piece of work the pattern was observed on")
public record InAppEvidenceDTO(
        @NonNull @Schema(description = "The piece of work, as every surface names it")
        ReviewedWorkRefDTO work,

        @NonNull @Schema(description = "When the measurement behind this occurrence was taken")
        Instant observedAt,

        @NonNull
        @Schema(
                description = "What the review made of this piece of work: a behaviour demonstrated, a trap"
                        + " avoided, something harmful done, or something needed left out",
                allowableValues = {"DEMONSTRATED_STRENGTH", "SAFE_AVOIDANCE", "COMMISSION_PROBLEM", "OMISSION_GAP"})
        ObservationOutcome outcome,

        @Schema(description = "What the review recorded on this piece of work") @Nullable
        String summary) {
    /**
     * @param target the run that produced the observation, when the lookup found it; without it the work is
     *     named by its kind alone
     */
    public static InAppEvidenceDTO from(Observation observation, @Nullable Target target) {
        return new InAppEvidenceDTO(
                ReviewedWorkLabels.ref(observation.getArtifactKind(), observation.getArtifactId(), target),
                observation.getObservedAt(),
                ObservationOutcome.of(observation),
                observation.getSummary());
    }
}
