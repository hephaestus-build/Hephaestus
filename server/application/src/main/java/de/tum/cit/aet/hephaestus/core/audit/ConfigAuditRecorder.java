package de.tum.cit.aet.hephaestus.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditUnavailableException;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes {@link ConfigAuditEvent} rows. Sole implementation of {@link ConfigAuditPort}.
 *
 * <p>Not {@code @ConditionalOnServerRole}: a producer on the webhook or worker role would fail to start
 * on a missing bean, and the trail is meant to accept writes from every role.
 */
@Component
@RequiredArgsConstructor
class ConfigAuditRecorder implements ConfigAuditPort {

    private final ConfigAuditEventRepository repository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ConfigAuditEntry entry) {
        requireWritableTransaction();

        JsonNode before = ConfigAuditSnapshotMapper.toNode(entry.before());
        JsonNode after = ConfigAuditSnapshotMapper.toNode(entry.after());
        List<String> changedKeys = ConfigAuditDiff.changedKeys(before, after);

        ConfigAuditAction action = entry.action();
        if (action == ConfigAuditAction.UPDATED && changedKeys.isEmpty()) {
            // Idempotent PATCH: recording it would bury real changes under noise in the resource's
            // history. Create/delete are never suppressed — they happened even if the state is empty.
            return;
        }

        repository.save(ConfigAuditEvent.create(
                clock.instant(),
                entry.workspaceId(),
                ConfigAuditActor.fromSecurityContext(entry.workspaceId()),
                entry.entityType(),
                entry.entityId(),
                action,
                changedKeys,
                ConfigAuditSnapshotMapper.toJson(before),
                ConfigAuditSnapshotMapper.toJson(after)));
    }

    /**
     * Fail fast unless a real, writable transaction is in progress.
     *
     * <p>{@code MANDATORY} alone is not enough, and an ArchUnit rule asserting the caller carries
     * {@code @Transactional} would not be either — both check for the annotation, not for what it
     * resolves to. Under a class-level {@code @Transactional(readOnly = true)} a transaction genuinely
     * exists, so {@code MANDATORY} is satisfied and the build stays green, but Hibernate sets
     * {@code FlushMode.MANUAL} and the INSERT is silently never flushed: a config change commits with
     * no audit row and nothing anywhere goes red. Hence the check against the runtime fact.
     */
    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ConfigAuditUnavailableException(
                    "config audit must be recorded inside the transaction that performs the change, "
                            + "otherwise the change can commit without its audit row");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new ConfigAuditUnavailableException(
                    "config audit was called inside a read-only transaction; the insert would never be "
                            + "flushed (FlushMode.MANUAL) and the change would commit with no audit row");
        }
    }
}
