package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * The size of the database, as the gauge {@code database.size} in bytes, from {@code pg_database_size} every five
 * minutes, and the size of the volume that the chart gave it, as {@code database.volume.capacity}.
 *
 * <p>Their ratio approximates how full the volume is: it leaves out the WAL, temporary files and the filesystem's own
 * share. It needs no kubelet metrics, so it also works with the bundled monitoring stack. The size gauge is NaN before
 * the first read and after a read that failed.
 */
@ConditionalOnServerRole
@Component
@EnableConfigurationProperties(DatabaseVolumeProperties.class)
@WorkspaceAgnostic("The database belongs to the instance, not to a workspace")
public class DatabaseSizeMonitor {

    static final String QUERY = "SELECT pg_database_size(current_database())";

    private static final Logger log = LoggerFactory.getLogger(DatabaseSizeMonitor.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicReference<@Nullable Long> size = new AtomicReference<>();
    private final Counter failures;

    public DatabaseSizeMonitor(JdbcTemplate jdbcTemplate, DatabaseVolumeProperties volume, MeterRegistry meters) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(CoreMetrics.DATABASE_SIZE, size, DatabaseSizeMonitor::bytes)
                .description(
                        "Bytes of the database, from pg_database_size; NaN before the first read and after a failed"
                                + " read")
                .baseUnit("bytes")
                .register(meters);
        DataSize capacity = volume.volumeSize();
        if (capacity != null) {
            Gauge.builder(CoreMetrics.DATABASE_VOLUME_CAPACITY, capacity, DataSize::toBytes)
                    .description("Bytes of the volume that holds the bundled PostgreSQL, as the chart sized it")
                    .baseUnit("bytes")
                    .register(meters);
        }
        this.failures = Counter.builder(CoreMetrics.DATABASE_SIZE_SAMPLER_FAILURES)
                .description("Reads of the database size that failed (the size gauge is NaN until the next read)")
                .register(meters);
    }

    @Scheduled(fixedDelay = 5, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void sample() {
        try {
            size.set(jdbcTemplate.queryForObject(QUERY, Long.class));
        } catch (RuntimeException e) {
            // Every failed read counts, or the alert on the failures could miss one that hides the size.
            size.set(null);
            failures.increment();
            log.atWarn()
                    .addKeyValue("event.name", "database.size.sampler_failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Database size read failed, reporting no value");
        }
    }

    private static double bytes(AtomicReference<@Nullable Long> latest) {
        Long value = latest.get();
        return value == null ? Double.NaN : value;
    }
}
