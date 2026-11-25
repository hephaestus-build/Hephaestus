package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.ControlledClock;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily sweeper that physically removes {@link IssuedJwt} rows whose {@code expires_at} is in the
 * past, so the revocation list stays bounded and the decoder's per-request {@code jti} lookup stays
 * cheap. An already-expired token is rejected by the JWT timestamp validator regardless of whether
 * its row still exists, so deleting it changes no auth decision — it only reclaims storage.
 *
 * <p>Wrapped in {@link SchedulerLock @SchedulerLock} so concurrent server pods don't both race the
 * DELETE — same pattern as {@code OAuthStateNonceCleanupJob}. The {@code ix_issued_jwt_expires_at}
 * index backs the range delete.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("The revocation list is account-scoped, not workspace-scoped")
public class IssuedJwtCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IssuedJwtCleanupJob.class);

    private final IssuedJwtRepository repository;
    private final Clock clock;
    private final Counter prunedCounter;

    public IssuedJwtCleanupJob(IssuedJwtRepository repository, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = ControlledClock.operationalClock(clock);
        this.prunedCounter = Counter.builder(CoreMetrics.AUTH_ISSUED_JWT_PRUNED)
                .description("Expired issued_jwt revocation rows physically removed by the daily sweep")
                .register(meterRegistry);
    }

    /** Runs daily at 03:30 server time. */
    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "issued-jwt-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpired() {
        int deleted = repository.deleteExpiredBefore(clock.instant());
        if (deleted > 0) {
            prunedCounter.increment(deleted);
        }
        log.info("IssuedJwtCleanupJob: pruned {} expired revocation rows", deleted);
    }
}
