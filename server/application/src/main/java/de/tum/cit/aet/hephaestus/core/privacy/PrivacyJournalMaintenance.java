package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Journal maintenance applies to instance-wide recovery facts")
class PrivacyJournalMaintenance {

    private static final Duration MAX_OBSERVATION_AGE = Duration.ofHours(30);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PrivacyJournalProperties properties;
    private final PrivacyJournalShipping shipping;
    private final MeterRegistry meters;
    private final TransactionTemplate transactions;

    @PostConstruct
    void registerBackupHorizonGauge() {
        Gauge.builder(
                        CoreMetrics.PRIVACY_JOURNAL_BACKUP_HORIZON_UNAVAILABLE,
                        this,
                        PrivacyJournalMaintenance::horizonUnavailable)
                .description("Whether backup recovery point metadata is missing or stale")
                .register(meters);
    }

    @Scheduled(cron = "0 45 1 * * *")
    @SchedulerLock(name = "privacyJournalRetention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1S")
    public void sweep() {
        prune(clock.instant());
    }

    @Scheduled(fixedDelay = 15000)
    @SchedulerLock(name = "privacyJournalShipping", lockAtMostFor = "PT3M", lockAtLeastFor = "PT1S")
    public void retryShipping() {
        shipping.retry();
    }

    int prune(Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Journal retention requires a separate database transaction");
        return Objects.requireNonNull(transactions.execute(status -> pruneWithStableHorizon(now)));
    }

    private int pruneWithStableHorizon(Instant now) {
        if (properties.backupsEnabled() && !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT pg_try_advisory_xact_lock_shared(hashtext('hephaestus'), hashtext('privacy-journal-backup-horizon'))
            """, Boolean.class))) {
            return 0;
        }
        if (horizonUnavailable(now)) return 0;
        // Only committed writer transactions are visible to this statement. The time the sweep sees
        // each row is therefore an upper bound on its commit time, including transactions that cross a backup.
        jdbc.update(
                "UPDATE privacy_journal SET retention_observed_at = clock_timestamp() WHERE retention_observed_at IS NULL");
        if (!properties.backupsEnabled()) {
            return jdbc.update(
                    "DELETE FROM privacy_journal WHERE retention_observed_at < ? AND (shipped_at IS NOT NULL OR (NOT requires_shipping AND NOT ?))",
                    Timestamp.from(now.minus(properties.retention())),
                    shipping.enabled());
        }
        // The shared lock keeps the verified report stable until the deletion commits.
        return jdbc.update(
                """
            DELETE FROM privacy_journal j USING privacy_journal_backup_horizon h
            WHERE h.id = 1 AND h.oldest_backup_at IS NOT NULL AND h.observed_at BETWEEN ? AND ?
              AND j.retention_observed_at < h.oldest_backup_at
              AND (j.shipped_at IS NOT NULL OR (NOT j.requires_shipping AND NOT ?))
            """, Timestamp.from(now.minus(MAX_OBSERVATION_AGE)), Timestamp.from(now), shipping.enabled());
    }

    private double horizonUnavailable() {
        try {
            return horizonUnavailable(clock.instant()) ? 1 : 0;
        } catch (DataAccessException unavailable) {
            return 1;
        }
    }

    private boolean horizonUnavailable(Instant now) {
        return properties.backupsEnabled()
                && !Boolean.TRUE.equals(jdbc.queryForObject(
                        """
            SELECT EXISTS (
                SELECT 1 FROM privacy_journal_backup_horizon
                WHERE id = 1 AND oldest_backup_at IS NOT NULL AND observed_at BETWEEN ? AND ?
            )
            """, Boolean.class, Timestamp.from(now.minus(MAX_OBSERVATION_AGE)), Timestamp.from(now)));
    }
}
