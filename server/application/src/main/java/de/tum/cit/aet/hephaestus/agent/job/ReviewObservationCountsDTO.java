package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.AssessmentCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Counts of observations by assessment")
public record ReviewObservationCountsDTO(
        @NonNull Long strengths,
        @NonNull Long problems,

        @NonNull @Schema(description = "Practices whose subject did not occur in this work")
        Long notApplicable,

        @NonNull
        @Schema(
                description = "Practices that looked at the evidence and could not settle the question either way; "
                        + "reported apart from notApplicable because one says there was nothing here to judge and the "
                        + "other says we could not tell")
        Long undetermined) {
    public static ReviewObservationCountsDTO empty() {
        return new ReviewObservationCountsDTO(0L, 0L, 0L, 0L);
    }

    static ReviewObservationCountsDTO from(@Nullable AssessmentCounts counts) {
        return counts == null
                ? empty()
                : new ReviewObservationCountsDTO(
                        counts.getStrengths(),
                        counts.getProblems(),
                        counts.getNotApplicable(),
                        counts.getUndetermined());
    }

    ReviewObservationCountsDTO plus(ReviewObservationCountsDTO other) {
        return new ReviewObservationCountsDTO(
                strengths + other.strengths,
                problems + other.problems,
                notApplicable + other.notApplicable,
                undetermined + other.undetermined);
    }
}
