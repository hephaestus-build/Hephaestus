package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin scheduling shell around {@link SyncJobService#reapAbandonedJobs}: once at startup (catches
 * anything abandoned by a restart while nothing was watching) and hourly thereafter. The manual-trigger
 * path additionally runs a connection-scoped reap inline (see {@link SyncJobService#beginJob}), so a
 * crashed prior run never blocks "Sync now" for the full hour between sweeps.
 *
 * <p>{@link ConditionalOnServerRole} rather than relying solely on {@code @EnableScheduling} being
 * server-scoped: the startup {@link EventListener} fires on every runtime role (it's a generic Spring
 * Boot event, not scheduling-gated), and the in-JVM handle registry this sweep interacts with is only
 * ever populated on the server role today (the manual REST trigger).
 */
@Component
@Profile("!specs & !cds-training")
@ConditionalOnServerRole
@WorkspaceAgnostic("Cross-workspace sync-job zombie sweep")
public class SyncJobZombieSweeper {

    private static final Logger log = LoggerFactory.getLogger(SyncJobZombieSweeper.class);

    private final SyncJobService syncJobService;

    public SyncJobZombieSweeper(SyncJobService syncJobService) {
        this.syncJobService = syncJobService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.HOURS)
    public void hourlySweep() {
        sweep();
    }

    private void sweep() {
        try {
            int reaped = syncJobService.reapAbandonedJobs();
            if (reaped > 0) {
                log.info("Sync job zombie sweep reaped {} abandoned job(s)", reaped);
            }
        } catch (Exception e) {
            log.warn("Sync job zombie sweep failed: {}", e.toString(), e);
        }
    }
}
