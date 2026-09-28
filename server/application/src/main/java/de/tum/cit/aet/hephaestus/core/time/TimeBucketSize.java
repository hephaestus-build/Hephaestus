package de.tum.cit.aet.hephaestus.core.time;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        enumAsRef = true,
        description = "How long each bucket of a range is: a day for ranges up to " + TimeBuckets.MAX_DAILY_DAYS
                + " days, a week from Monday for ranges up to " + TimeBuckets.MAX_WEEKLY_DAYS
                + " days, a month for longer ones")
public enum TimeBucketSize {
    DAY,
    WEEK,
    MONTH
}
