package de.tum.cit.aet.hephaestus.core.time;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/** A range as {@link TimeRangeFilterParams} reads it, split into buckets in a time zone. */
public record TimeBucketParams(
        @Parameter(description = TimeRangeFilterParams.FROM_DESCRIPTION)
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant from,

        @Parameter(description = TimeRangeFilterParams.TO_DESCRIPTION)
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant to,

        @Parameter(
                description = "The IANA time zone whose midnights start the buckets, such as Europe/Berlin",
                schema = @Schema(defaultValue = TimeBucketParams.DEFAULT_ZONE))
        @RequestParam(required = false)
        @Nullable
        String zone) {

    static final String DEFAULT_ZONE = "UTC";

    /** The range with its defaults filled in, and its buckets; a bad range or time zone is rejected. */
    public TimeBuckets toBuckets(Clock clock) {
        return TimeBuckets.of(new TimeRangeFilterParams(from, to).toRange(clock), zoneId());
    }

    /**
     * An ID from Java's time zone database, such as Europe/Berlin or UTC; a bare offset such as +02:00 is rejected
     * because it keeps one clock across a daylight-saving change.
     */
    private ZoneId zoneId() {
        String id = zone != null ? zone : DEFAULT_ZONE;
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + id);
        }
        return ZoneId.of(id);
    }
}
