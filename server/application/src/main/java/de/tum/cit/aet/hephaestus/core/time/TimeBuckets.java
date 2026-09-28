package de.tum.cit.aet.hephaestus.core.time;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * The time buckets of a range in a time zone, oldest first: every day, week or month the range touches. The first
 * starts at or before the range; the last holds its final instant. The zone is resolved here, with Java's time zone
 * database, and the database only ever sees the instants the buckets start at.
 */
public record TimeBuckets(TimeRange range, TimeBucketSize size, List<Instant> starts) {

    static final int MAX_DAILY_DAYS = 31;
    static final int MAX_WEEKLY_DAYS = 184;

    public static TimeBuckets of(TimeRange range, ZoneId zone) {
        Duration length = Duration.between(range.from(), range.to());
        TimeBucketSize size = length.compareTo(Duration.ofDays(MAX_DAILY_DAYS)) <= 0
                ? TimeBucketSize.DAY
                : length.compareTo(Duration.ofDays(MAX_WEEKLY_DAYS)) <= 0 ? TimeBucketSize.WEEK : TimeBucketSize.MONTH;
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
        return new TimeBuckets(range, size, List.copyOf(starts));
    }

    /**
     * The starts as seconds since the epoch, for {@code width_bucket(extract(epoch from ...), ...)}, which numbers
     * each instant by the last start at or before it, from 1. Comparing seconds keeps the time zone of the JVM and
     * of the database session out of it.
     */
    public Long[] epochSeconds() {
        return starts.stream().map(Instant::getEpochSecond).toArray(Long[]::new);
    }

    private static LocalDate truncate(TimeBucketSize size, LocalDate day) {
        return switch (size) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }
}
