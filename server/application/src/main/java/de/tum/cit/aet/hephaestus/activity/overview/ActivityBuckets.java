package de.tum.cit.aet.hephaestus.activity.overview;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The time buckets of a range in a time zone, oldest first: every day, week or month the range touches. The first
 * starts at or before the range; the last holds its final instant. The zone is resolved here, with Java's time zone
 * database, and the database only ever sees the instants the buckets start at.
 */
record ActivityBuckets(ActivityBucketSize size, List<Instant> starts) {

    static final Duration MAX_DAILY = Duration.ofDays(31);
    static final Duration MAX_WEEKLY = Duration.ofDays(184);

    static ActivityBuckets of(ActivityRange range, ZoneId zone) {
        Duration length = Duration.between(range.from(), range.to());
        ActivityBucketSize size = length.compareTo(MAX_DAILY) <= 0
                ? ActivityBucketSize.DAY
                : length.compareTo(MAX_WEEKLY) <= 0 ? ActivityBucketSize.WEEK : ActivityBucketSize.MONTH;
        List<Instant> starts = new ArrayList<>();
        if (range.from().isBefore(range.to())) {
            LocalDate day = truncate(size, LocalDate.ofInstant(range.from(), zone));
            for (Instant start = day.atStartOfDay(zone).toInstant();
                    start.isBefore(range.to());
                    start = day.atStartOfDay(zone).toInstant()) {
                starts.add(start);
                day = switch (size) {
                    case DAY -> day.plusDays(1);
                    case WEEK -> day.plusWeeks(1);
                    case MONTH -> day.plusMonths(1);
                };
            }
        }
        return new ActivityBuckets(size, List.copyOf(starts));
    }

    /** A time zone by its IANA name. */
    static ZoneId zone(String id) {
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + id);
        }
        return ZoneId.of(id);
    }

    private static LocalDate truncate(ActivityBucketSize size, LocalDate day) {
        return switch (size) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }
}
