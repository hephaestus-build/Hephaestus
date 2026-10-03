package de.tum.cit.aet.hephaestus.integration.core.framework;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/** Cron helpers shared by the sync-state providers. */
public final class CronSchedules {

    private static final Logger log = LoggerFactory.getLogger(CronSchedules.class);

    private CronSchedules() {}

    /**
     * Next fire time of {@code cron} in the server's default zone — matching {@code @Scheduled(cron=...)}'s
     * un-zoned behavior. Returns {@code null} when the expression is unparseable or has no future run. A
     * parse failure is logged at debug (this is recomputed on every overview load, so a louder level would
     * spam) rather than swallowed silently, keeping one consistent policy across all providers.
     */
    @Nullable
    public static Instant nextRun(String cron) {
        try {
            LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.now(ZoneId.systemDefault()));
            return next == null ? null : next.atZone(ZoneId.systemDefault()).toInstant();
        } catch (IllegalArgumentException e) {
            log.debug("Unparseable sync cron '{}': {}", cron, e.getMessage());
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
     * judged against anyway.
     *
     * @return the cadence, or {@code null} when the expression is unparseable or has fewer than two
     *         future runs — callers must then decline to judge staleness rather than invent a default
     */
    @Nullable
    public static Duration interval(String cron) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime first = expression.next(LocalDateTime.now(ZoneId.systemDefault()));
            if (first == null) {
                return null;
            }
            LocalDateTime second = expression.next(first);
            if (second == null) {
                return null;
            }
            return Duration.between(first, second);
        } catch (IllegalArgumentException e) {
            log.debug("Unparseable sync cron '{}': {}", cron, e.getMessage());
            return null;
        }
    }
}
