package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether the database's WAL archive fails, as the gauge {@code database.wal.archive.failing}, from one read of
 * {@code pg_stat_archiver} each 30 seconds, never one read per scrape.
 *
 * <p>No reading, no claim: the gauge is NaN before the first read, while {@code archive_mode} is off, and after a
 * read that failed. A failed read does not keep the last value, because that value could claim a healthy archive
 * that nobody checked.
 *
 * <p>No age gauge: PostgreSQL switches a segment on {@code archive_timeout} only after WAL activity, so an idle
 * instance archives nothing and an age would page falsely. A hung archive command stops the next backup and fills
 * the volume, which the backup and volume alerts show.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("The WAL archive belongs to the database instance, not to a workspace")
public class WalArchiveMonitor {

    static final String QUERY = "SELECT current_setting('archive_mode') <> 'off' AS archiving,"
            + " last_archived_time, last_failed_time FROM pg_stat_archiver";

    private static final Logger log = LoggerFactory.getLogger(WalArchiveMonitor.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicReference<@Nullable Reading> latest = new AtomicReference<>();
    private final Counter sampleFailures;

    public WalArchiveMonitor(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(CoreMetrics.DATABASE_WAL_ARCHIVE_FAILING, latest, WalArchiveMonitor::failing)
                .description("1 when the latest WAL archive attempt failed, 0 when it succeeded or none failed; NaN"
                        + " before the first read, after a failed read and while archive_mode is off")
                .register(meterRegistry);
        this.sampleFailures = Counter.builder(CoreMetrics.DATABASE_WAL_ARCHIVE_SAMPLE_FAILURES)
                .description("Reads of pg_stat_archiver that failed (the gauge is NaN until the next read)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 30, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            latest.set(read());
        } catch (RuntimeException e) {
            latest.set(null);
            sampleFailures.increment();
            log.warn(
                    "WAL archive read failed, reporting no value: errorType={}",
                    e.getClass().getName());
        }
    }

    @Nullable
    Reading read() {
        return jdbcTemplate.queryForObject(QUERY, (resultSet, row) -> Reading.of(resultSet));
    }

    private static double failing(AtomicReference<@Nullable Reading> latest) {
        Reading reading = latest.get();
        return reading == null ? Double.NaN : reading.failing();
    }

    /** One row of {@code pg_stat_archiver}. Both times are null until the first attempt of their kind. */
    record Reading(
            boolean archiving,
            @Nullable Instant lastArchived,
            @Nullable Instant lastFailed) {

        static Reading of(ResultSet resultSet) throws SQLException {
            return new Reading(
                    resultSet.getBoolean("archiving"),
                    instant(resultSet, "last_archived_time"),
                    instant(resultSet, "last_failed_time"));
        }

        private static @Nullable Instant instant(ResultSet resultSet, String column) throws SQLException {
            OffsetDateTime time = resultSet.getObject(column, OffsetDateTime.class);
            return time == null ? null : time.toInstant();
        }

        /** A failure that no success followed: PostgreSQL retries the same segment until it is archived. */
        double failing() {
            if (!archiving) {
                return Double.NaN;
            }
            if (lastFailed == null) {
                return 0;
            }
            return lastArchived == null || lastFailed.isAfter(lastArchived) ? 1 : 0;
        }
    }
}
