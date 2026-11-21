package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ages out who changed a connection and why: a connection audit row keeps its admin and free text no longer than the
 * configuration audit keeps its rows. The row itself stays until workspace purge, because the replay guard
 * {@link ConnectionAuditRepository#containsEvent} reads it. A GitHub installation binds under one key for its whole
 * life, so a deleted bind row would let the next startup reconcile resume a connection that an admin suspended.
 */
@ConditionalOnServerRole
@Component
@RequiredArgsConstructor
class ConnectionAuditRetentionJob {

    /** The window of the configuration audit and the authentication events. */
    static final int RETENTION_DAYS = 365;

    private static final Logger log = LoggerFactory.getLogger(ConnectionAuditRetentionJob.class);

    private final ConnectionAuditRepository audits;

    @Scheduled(cron = "0 25 0 * * *")
    @SchedulerLock(name = "connection-audit-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Retention ages out rows across every workspace; there is no single tenant to scope it to")
    @Transactional
    public void sweep() {
        int scrubbed = audits.scrubOlderThan(RETENTION_DAYS);
        if (scrubbed > 0)
            log.info(
                    "connection.audit: retention removed the actor of {} rows older than {} days",
                    scrubbed,
                    RETENTION_DAYS);
    }
}
