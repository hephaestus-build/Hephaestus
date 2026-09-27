package de.tum.cit.aet.hephaestus.activity.overview.dto;

import de.tum.cit.aet.hephaestus.activity.overview.ActivityBucketSize;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

@Schema(description = "Activity in a time range, in total and over time")
public record ActivityOverviewDTO(
        @NonNull @Schema(description = "The activity in the range; the buckets' summaries add up to it")
        ActivitySummaryDTO summary,

        @NonNull
        @Schema(
                description = "How long each bucket is: a day for ranges up to 31 days, a week for ranges up to 184"
                        + " days, a month for longer ones")
        ActivityBucketSize bucket,

        @NonNull @Schema(description = "Every bucket the range touches, oldest first, including those without activity")
        List<ActivityBucketDTO> buckets) {}
