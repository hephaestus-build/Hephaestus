package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily sweeper for {@link OAuthStateNonce} rows older than the configured retention
 * window (default 7 days, {@code hephaestus.integration.oauth-state.nonce-retention}).
 * With a 10-minute HMAC TTL, anything past a few hours is guaranteed unrecoverable —
 * pruning bounds storage and keeps the {@link OAuthStateNonceRepository#markConsumedInWorkspace}
 * hot path cheap. {@link SchedulerLock @SchedulerLock} keeps concurrent server pods
 * from racing the DELETE.
 */
@ConditionalOnServerRole
@Component
@WorkspaceAgnostic("Pruning a workspace-agnostic table")
public class OAuthStateNonceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateNonceCleanupJob.class);

    private final OAuthStateNonceRepository repository;
    private final Duration retention;
    private final Counter prunedCounter;

    /** Spring-wired constructor; retention binds via {@link OAuthStateProperties}. */
    @Autowired
    public OAuthStateNonceCleanupJob(
            OAuthStateNonceRepository repository, OAuthStateProperties properties, MeterRegistry meterRegistry) {
        this(repository, properties.nonceRetention(), meterRegistry);
    }

    /** Canonical constructor (also the unit-test seam): retention passed directly. */
    public OAuthStateNonceCleanupJob(
            OAuthStateNonceRepository repository, @Nullable Duration retention, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.retention = retention == null ? Duration.ofDays(7) : retention;
        this.prunedCounter = Counter.builder(IntegrationCoreMetrics.OAUTH_STATE_NONCE_PRUNED)
                .description("Number of OAuth state nonces pruned by the daily sweep")
                .register(meterRegistry);
    }

    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "oauth-state-nonce-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = repository.deleteByIssuedAtBefore(cutoff);
        if (deleted > 0) {
            prunedCounter.increment(deleted);
        }
        log.info(
                "OAuthStateNonceCleanupJob: pruned {} nonces older than {} (retention={})", deleted, cutoff, retention);
    }
}
