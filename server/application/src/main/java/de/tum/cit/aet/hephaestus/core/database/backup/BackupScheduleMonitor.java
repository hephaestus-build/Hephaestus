package de.tum.cit.aet.hephaestus.core.database.backup;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How old the newest backup of the bundled PostgreSQL is, as the gauge {@code database.backup.age}, from one read of
 * the backup schedules every five minutes.
 *
 * <p>The age counts from the newest successful backup of any schedule. Before a schedule's first success, it counts
 * from the oldest schedule's creation, so a first backup that never runs also grows old. A backup Job that waits
 * Pending never succeeds, so its age grows too.
 *
 * <p>Fail-safe: the gauge is infinite before the first read, after a read that failed, and while a schedule is
 * missing. It never keeps an older value or reports a default as a fresh backup.
 */
@ConditionalOnServerRole
@ConditionalOnProperty(prefix = "hephaestus.database.backup", name = "cron-jobs")
@Component
@WorkspaceAgnostic("The database backups belong to the instance, not to a workspace")
public class BackupScheduleMonitor {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduleMonitor.class);

    private final BackupSchedules schedules;
    private final List<String> names;
    private final AtomicReference<@Nullable Instant> since = new AtomicReference<>();
    private final Counter failures;

    public BackupScheduleMonitor(
            BackupSchedules schedules, BackupScheduleProperties properties, Clock clock, MeterRegistry meters) {
        this.schedules = schedules;
        this.names = List.copyOf(properties.cronJobs());
        Gauge.builder(
                        CoreMetrics.DATABASE_BACKUP_AGE,
                        since,
                        (AtomicReference<@Nullable Instant> anchor) -> ageSeconds(anchor, clock))
                .description("Seconds since the newest successful backup, or since the schedules were created before"
                        + " the first; infinite before the first read, after a failed read and while a schedule is"
                        + " missing")
                .baseUnit("seconds")
                .register(meters);
        this.failures = Counter.builder(CoreMetrics.DATABASE_BACKUP_SAMPLE_FAILURES)
                .description("Reads of the backup schedules that failed or found a schedule missing")
                .register(meters);
    }

    @Scheduled(fixedDelay = 5, initialDelay = 0, timeUnit = TimeUnit.MINUTES)
    public void sample() {
        try {
            Instant anchor = anchor();
            since.set(anchor);
            if (anchor == null) {
                failures.increment();
            }
        } catch (RuntimeException e) {
            since.set(null);
            failures.increment();
            log.warn(
                    "Backup schedule read failed, reporting an infinite age: errorType={}",
                    e.getClass().getName());
        }
    }

    /** The newest success of any schedule, else the oldest creation; null when a schedule is missing or none is set. */
    @Nullable
    Instant anchor() {
        Instant newestSuccess = null;
        Instant oldestCreated = null;
        for (String name : names) {
            BackupSchedules.Schedule schedule = schedules.read(name).orElse(null);
            if (schedule == null) {
                log.warn("Backup schedule missing, reporting an infinite age: schedule={}", name);
                return null;
            }
            Instant success = schedule.lastSuccess();
            if (success != null && (newestSuccess == null || success.isAfter(newestSuccess))) {
                newestSuccess = success;
            }
            if (oldestCreated == null || schedule.created().isBefore(oldestCreated)) {
                oldestCreated = schedule.created();
            }
        }
        return newestSuccess != null ? newestSuccess : oldestCreated;
    }

    private static double ageSeconds(AtomicReference<@Nullable Instant> since, Clock clock) {
        Instant anchor = since.get();
        if (anchor == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, Duration.between(anchor, clock.instant()).toMillis() / 1000.0);
    }
}
