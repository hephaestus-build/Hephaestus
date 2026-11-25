package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
class PrivacyJournalShipping {

    static final ScopedValue<UUID> ERASURE_REQUEST = ScopedValue.newInstance();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Optional<PrivacyJournalObjectStore> bucket;
    private final MeterRegistry meters;

    @PostConstruct
    void registerLag() {
        if (enabled()) {
            Gauge.builder(CoreMetrics.PRIVACY_JOURNAL_BUCKET_VERIFIED, this, PrivacyJournalShipping::bucketVerified)
                    .description("Whether the journal bucket supports conditional writes and stored SHA-256 checksums")
                    .register(meters);
        }
        Gauge.builder(CoreMetrics.PRIVACY_JOURNAL_SHIPPING_LAG, this, PrivacyJournalShipping::lagSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest privacy fact awaiting its journal bucket copy")
                .register(meters);
    }

    private double lagSeconds() {
        return Objects.requireNonNull(jdbc.queryForObject("""
            SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - MIN(recorded_at)), 0)
            FROM privacy_journal WHERE shipped_at IS NULL AND (requires_shipping OR ?)
            """, Double.class, enabled()));
    }

    private double bucketVerified() {
        return bucket.filter(PrivacyJournalObjectStore::verified).isPresent() ? 1 : 0;
    }

    boolean enabled() {
        return bucket.isPresent();
    }

    boolean erasurePending(UUID requestId) {
        return !pendingErasureEntries(requestId).isEmpty();
    }

    private List<UUID> pendingErasureEntries(UUID requestId) {
        return jdbc.query(
                """
                SELECT j.entry_id FROM privacy_journal j
                WHERE j.shipped_at IS NULL AND (j.requires_shipping OR ?) AND (
                    j.erasure_request_id = ? OR (j.kind IN ('ACCOUNT_ERASED', 'PERSON_ERASED')
                      AND (j.entry_id = ? OR j.refs->>'accountId' = (SELECT scope_json::jsonb->>'accountId'
                          FROM person_data_request WHERE id = ?))))
                ORDER BY j.id
                """,
                (row, index) -> Objects.requireNonNull(row.getObject(1, UUID.class)),
                enabled(),
                requestId,
                requestId,
                requestId);
    }

    boolean accountErasurePending(long accountId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM privacy_journal
                    WHERE shipped_at IS NULL AND (requires_shipping OR ?) AND (
                    (kind IN ('ACCOUNT_ERASED', 'PERSON_ERASED') AND refs->>'accountId' = ?) OR erasure_request_id IN (
                        SELECT id FROM person_data_request WHERE scope_json::jsonb->>'accountId' = ?)))
                """, Boolean.class, enabled(), Long.toString(accountId), Long.toString(accountId)));
    }

    /** Invoked after the cause commits. No database transaction remains open while S3 answers. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void confirm(UUID entryId) {
        confirmStoredCopy(entryId);
    }

    private void confirmStoredCopy(UUID entryId) {
        try {
            confirmCopy(entryId);
        } catch (DataAccessException failure) {
            log.warn(
                    "Privacy journal proof is pending: entryId={}, error={}",
                    entryId,
                    failure.getClass().getSimpleName());
            throw new JournalPendingException(failure);
        }
    }

    private void confirmCopy(UUID entryId) {
        var store = bucket.orElse(null);
        if (store == null) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM privacy_journal
                        WHERE entry_id = ? AND requires_shipping AND shipped_at IS NULL)
                    """, Boolean.class, entryId))) {
                throw new JournalPendingException();
            }
            return;
        }
        ship(entryId, store);
    }

    void pendingErasureReply(UUID requestId) {
        if (!PrivacyJournalReply.isBound()) return;
        pendingErasureEntries(requestId).forEach(PrivacyJournalReply::committed);
    }

    /** Recovery runs with schedules disabled and must finish each fact before the next erasure step. */
    void confirmRecoveryErasure(UUID requestId) {
        for (var entryId : pendingErasureEntries(requestId)) confirmStoredCopy(entryId);
    }

    private void ship(UUID entryId, PrivacyJournalObjectStore store) {
        var entries = jdbc.query(
                """
                SELECT j.kind, j.refs, j.recorded_at
                FROM privacy_journal j
                WHERE j.entry_id = ? AND j.shipped_at IS NULL
                """,
                (row, index) -> new PrivacyJournalObject(
                        entryId,
                        PrivacyJournalKind.valueOf(Objects.requireNonNull(row.getString(1))),
                        mapper.readTree(Objects.requireNonNull(row.getString(2))),
                        Objects.requireNonNull(row.getTimestamp(3)).toInstant()),
                entryId);
        if (entries.isEmpty()) return;
        var entry = entries.getFirst();
        if (!store.store(entry.relativeKey(), mapper.writeValueAsBytes(entry))) throw new JournalPendingException();
        jdbc.update("""
            UPDATE privacy_journal SET shipped_at = clock_timestamp()
            WHERE entry_id = ? AND shipped_at IS NULL
            """, entryId);
    }

    /** Retries committed facts after a timeout or restart. Conditional writes make concurrent retries safe. */
    void retry() {
        var store = bucket.orElse(null);
        if (store == null) return;
        var pending = jdbc.queryForList("""
            SELECT entry_id FROM privacy_journal WHERE shipped_at IS NULL
            ORDER BY shipping_attempted_at NULLS FIRST, id LIMIT 10
            """, UUID.class);
        for (UUID entryId : pending) {
            try {
                jdbc.update("""
                    UPDATE privacy_journal SET shipping_attempted_at = clock_timestamp()
                    WHERE entry_id = ? AND shipped_at IS NULL
                    """, entryId);
                ship(Objects.requireNonNull(entryId), store);
            } catch (JournalPendingException unavailable) {
                // Keep this fact in the queue and let other entries make progress.
            }
        }
    }
}
