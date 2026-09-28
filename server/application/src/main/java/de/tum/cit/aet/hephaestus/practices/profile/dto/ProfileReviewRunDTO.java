package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewObservationCountsDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One review run as the developer's own page reads it: when it ran, on what, how it ended, and what it
 * found about this developer. Every count is narrowed to them — a run over a shared pull request says
 * nothing here about anybody else's work.
 */
@Schema(description = "One review run on the developer's own work, with what it found about them")
public record ProfileReviewRunDTO(
        @NonNull UUID reviewId,

        @NonNull
        @Schema(
                description = "When the run stopped, once it has stopped, and when it began while it is still"
                        + " going. A run whose own start and end were never recorded falls back to when it wrote"
                        + " its newest observation about this developer.")
        Instant reviewedAt,

        @NonNull @Schema(description = "The piece of work the run reviewed")
        ReviewedWorkRefDTO reviewedWork,

        @Nullable @Schema(description = "How the run ended; absent when the run itself is no longer on record")
        ReviewRunState status,

        @Nullable @Schema(description = "What occasioned the run: MANUAL is one a person asked for; absent with status")
        TriggerMode triggerMode,

        @Nullable @Schema(description = "The sentence the run opened its feedback with; absent when it wrote none")
        String lead,

        @Nullable
        @Schema(
                description =
                        "How many practices the run measured; absent when it wrote no coverage it can stand behind")
        Integer practicesEvaluated,

        @Nullable
        @Schema(
                description = "How many practices the run was eligible for; absent when it wrote no such count it"
                        + " can stand behind")
        Integer practicesEligible,

        @Nullable
        @Schema(
                description = "How long the run took, start to finish; absent while it is still going, and whenever"
                        + " its start and end were not both recorded")
        Long durationSeconds,

        @NonNull @Schema(description = "What the run observed about this developer, by assessment")
        ReviewObservationCountsDTO observations,

        @NonNull @Schema(description = "How many pieces of feedback from this run reached this developer")
        Long feedbackDelivered,

        @Nullable
        @Schema(
                description = "Where the feedback this run left on the work is read, at the provider; absent"
                        + " whenever the comment it landed in cannot be addressed from the work's own page")
        String feedbackUrl,

        @NonNull
        @Schema(
                description = "The practices this run recorded a problem about for this developer, in the order"
                        + " it recorded them and at most three")
        List<SlippedPracticeDTO> slippedPractices,

        @NonNull
        @Schema(
                description = "Whether this reader may ask for a review of this work now, as the request front door"
                        + " would answer them. False also when the kind of work admits no request at all.")
        Boolean mayRequest) {}
