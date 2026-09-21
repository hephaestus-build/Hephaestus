package de.tum.cit.aet.hephaestus.practices.groupdetail.dto;

import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A complete review run in a developer's practice-group history")
public record PracticeGroupReviewRunDTO(
        @NonNull UUID reviewId,
        @NonNull Instant reviewedAt,
        @NonNull ReviewedWorkRefDTO reviewedWork,

        @Nullable
        @Schema(
                description = "The one sentence the review opened with about this piece of work "
                        + "(null when it wrote none)")
        String lead,

        @Nullable @Schema(description = "How many of the practices this run was eligible for it actually reached")
        Integer practicesEvaluated,

        @Nullable @Schema(description = "How many practices this run was eligible to review")
        Integer practicesEligible,

        @Nullable @Schema(description = "How long the run took, in seconds (null while it has not finished)")
        Long durationSeconds,

        @NonNull @Schema(description = "Every visible observation of the run, complete enough to open in place")
        List<ObservationDetailDTO> observations) {}
