package de.tum.cit.aet.hephaestus.agent.job;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

@Schema(description = "Practice reviews, observations and feedback in one time bucket of a range")
public record PracticeReviewBucketDTO(
        @NonNull
        @Schema(
                description = "When the bucket starts: midnight of its day, of its week's Monday or of its month's"
                        + " first day, in the requested time zone. The first bucket may start before the range.")
        Instant start,

        @NonNull @Schema(description = "Practice reviews created in the bucket within the range, in any status")
        Long reviews,

        @NonNull @Schema(description = "Observations recorded in the bucket within the range")
        Long observations,

        @NonNull @Schema(description = "Feedback created in the bucket within the range, in any delivery state")
        Long feedback) {}
