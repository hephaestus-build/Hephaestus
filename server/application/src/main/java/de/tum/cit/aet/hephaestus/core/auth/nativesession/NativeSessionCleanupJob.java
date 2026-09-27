package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes native sessions past their deadline or revoked a day ago, and handoff codes past their expiry.
 * Runs before {@code IssuedJwtCleanupJob}, which keeps the token a live native session backs, so an ended
 * session releases that row on the same night.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Native sessions are account-scoped, not workspace-scoped")
public class NativeSessionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NativeSessionCleanupJob.class);

    /** A revoked row stays a day so a late refresh or sign-out with its secret still finds it. */
    private static final Duration REVOKED_RETENTION = Duration.ofDays(1);

    private final NativeSessionRepository sessionRepository;
    private final NativeSignInHandoffRepository handoffRepository;
    private final Clock clock;

    public NativeSessionCleanupJob(
            NativeSessionRepository sessionRepository, NativeSignInHandoffRepository handoffRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.handoffRepository = handoffRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "native-session-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        Instant now = clock.instant();
        int sessions = sessionRepository.deleteEnded(now, now.minus(REVOKED_RETENTION));
        int handoffs = handoffRepository.deleteExpiredBefore(now);
        log.info("NativeSessionCleanupJob: removed {} ended sessions and {} expired handoffs", sessions, handoffs);
    }
}
