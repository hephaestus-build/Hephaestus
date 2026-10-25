package de.tum.cit.aet.hephaestus.integration.core.framework;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/** Cron helpers shared by the sync-state providers. */
public final class CronSchedules {

    private static final Logger log = LoggerFactory.getLogger(CronSchedules.class);

    private CronSchedules() {}

    /**
     * Next fire time of {@code cron} after {@code clock}'s instant, in the server's default zone — matching
     * {@code @Scheduled(cron=...)}'s un-zoned behavior. Returns {@code null} when the expression is unparseable or
     * has no future run. A parse failure is logged at debug (this is recomputed on every overview load, so a louder
     * level would spam) rather than swallowed silently, keeping one consistent policy across all providers.
     */
    @Nullable
    public static Instant nextRun(String cron, Clock clock) {
        return nextRun(cron, scheduledNow(clock));
    }

    /** {@link #nextRun(String, Clock)} from {@code now}, in the zone {@code now} carries. */
    @Nullable
    static Instant nextRun(String cron, ZonedDateTime now) {
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(now);
            return next == null ? null : next.toInstant();
        } catch (IllegalArgumentException e) {
            log.debug("Unparseable sync cron '{}': {}", cron, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The gap between {@code cron}'s next two fire times — the schedule's cadence, used to judge whether
     * a resource's {@code lastSyncedAt} is stale.
     *
     * <p>Measured rather than declared, so it stays correct for any expression without a table of
     * special cases. The tradeoff is deliberate: for an irregular schedule (e.g. weekdays only) this
     * returns the gap that happens to follow <em>now</em>, which is the cadence a "next sync" reading is
     * judged against anyway. The gap is wall-clock time: on the day the clocks go back, a schedule in the
     * repeated hour fires twice an hour apart, and elapsed time would report that hour as its cadence.
     *
     * @return the cadence, or {@code null} when the expression is unparseable or has fewer than two
     *         future runs — callers must then decline to judge staleness rather than invent a default
     */
    @Nullable
    public static Duration interval(String cron, Clock clock) {
        return interval(cron, scheduledNow(clock));
    }

    /** {@link #interval(String, Clock)} from {@code now}, in the zone {@code now} carries. */
    @Nullable
    static Duration interval(String cron, ZonedDateTime now) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime first = expression.next(now.toLocalDateTime());
            if (first == null) {
                return null;
            }
            LocalDateTime second = expression.next(first);
            if (second == null) {
                return null;
            }
            return Duration.between(first, second);
        } catch (IllegalArgumentException e) {
            log.debug("Unparseable sync cron '{}': {}", cron, e.getClass().getSimpleName());
            return null;
        }
    }

    // The application clock is UTC; the scheduler fires in the JVM's default zone.
    private static ZonedDateTime scheduledNow(Clock clock) {
        return clock.instant().atZone(ZoneId.systemDefault());
    }
}
