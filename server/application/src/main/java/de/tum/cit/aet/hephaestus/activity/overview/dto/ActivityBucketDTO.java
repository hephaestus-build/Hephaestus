package de.tum.cit.aet.hephaestus.activity.overview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

@Schema(description = "Activity in one time bucket of a range")
public record ActivityBucketDTO(
        @NonNull
        @Schema(
                description = "When the bucket starts: midnight of its day, of its week's Monday or of its month's"
                        + " first day, in the requested time zone. The first bucket may start before the range.")
        Instant start,

        @NonNull @Schema(description = "The activity in the bucket that falls within the range")
        ActivitySummaryDTO summary) {}
