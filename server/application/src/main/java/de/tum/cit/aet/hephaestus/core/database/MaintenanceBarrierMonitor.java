package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.core.MaintenanceBarrier;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether a migration holds or waits for the maintenance barrier ({@link MaintenanceBarrier}), as the gauge
 * {@code maintenance.barrier.raised}, from one read each 15 seconds, never one read per scrape. The read takes the
 * barrier's lock shared for its statement only, so it delays no migration.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("The barrier belongs to the database, not to a workspace")
public class MaintenanceBarrierMonitor {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceBarrierMonitor.class);

    private final JdbcTemplate jdbc;
    private final AtomicReference<@Nullable Boolean> raised = new AtomicReference<>();

    public MaintenanceBarrierMonitor(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        Gauge.builder(CoreMetrics.MAINTENANCE_BARRIER_RAISED, raised, MaintenanceBarrierMonitor::gauge)
                .description("1 while a database migration holds or waits for the barrier, so no server starts new"
                        + " work, 0 otherwise; NaN before the first read and after a failed read")
                .register(meters);
    }

    @Scheduled(fixedDelay = 15, initialDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void sample() {
        try {
            raised.set(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + MaintenanceBarrier.LIFTED, Boolean.class)));
        } catch (RuntimeException e) {
            raised.set(null);
            log.warn(
                    "Maintenance barrier read failed, reporting no value: errorType={}",
                    e.getClass().getName());
        }
    }

    private static double gauge(AtomicReference<@Nullable Boolean> raised) {
        Boolean value = raised.get();
        return value == null ? Double.NaN : value ? 1 : 0;
    }
}
