package de.tum.cit.aet.hephaestus.agent.job;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

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
}
