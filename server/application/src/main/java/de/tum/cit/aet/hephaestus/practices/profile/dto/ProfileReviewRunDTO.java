package de.tum.cit.aet.hephaestus.practices.profile.dto;

import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One review run on the developer's own work, with what it found about them")
public record ProfileReviewRunDTO(
        @NonNull UUID reviewId,

        @NonNull @Schema(description = "When the review recorded its newest observation about this developer")
        Instant reviewedAt,

        @NonNull @Schema(description = "The piece of work the run reviewed")
        ReviewedWorkRefDTO reviewedWork,

        @Nullable @Schema(description = "Where the review stands; absent when the review itself is no longer on record")
        ReviewRunState status,

        @Nullable
        @Schema(
                description = "What occasioned the review: MANUAL is one a person asked for; absent when the review"
                        + " itself is no longer on record")
        TriggerMode triggerMode,

        @Nullable @Schema(description = "How many practices the run measured; absent when it wrote no coverage ledger")
        Integer practicesEvaluated,

        @NonNull @Schema(description = "What the run decided about each practice it observed for this developer")
        ReviewPracticeOutcomesDTO practices,

        @NonNull @Schema(description = "How many pieces of feedback from this run reached this developer")
        Long feedbackDelivered,

        @Nullable
        @Schema(
                description = "Where the feedback this run left on the work is read, at the provider; absent"
                        + " whenever the comment it landed in cannot be addressed from the work's own page")
        String feedbackUrl,

        @NonNull
        @Schema(
                description =
                        "Every practice counted in practices.toImprove, once each, in order of practice" + " name")
        List<SlippedPracticeDTO> slippedPractices,

        @NonNull
        @Schema(
                description = "Whether this reader has standing to ask for a review of this work. False when the"
                        + " kind of work admits no request.")
        Boolean mayRequest) {}
