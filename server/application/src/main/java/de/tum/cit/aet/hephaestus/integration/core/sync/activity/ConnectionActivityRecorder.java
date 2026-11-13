package de.tum.cit.aet.hephaestus.integration.core.sync.activity;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records the webhook-liveness ("last event processed") watermark for a connection, from the provider inbox
 * consumer's hot post-dispatch path ({@code ProviderEventConsumer#handleMessage}).
 *
 * <p>Two in-memory caches keep the hot path cheap:
 * <ul>
 *   <li>{@code (workspaceId, kind) -> connectionId} — resolved once via {@link ConnectionRepository},
 *       re-resolved only after {@link #invalidate(long, IntegrationKind)} is called (e.g. on
 *       reconnect) or on first use.</li>
 *   <li>{@code connectionId -> lastWriteAt} — throttles the DB write itself to at most once per
 *       {@link #WRITE_THROTTLE} per connection, independent of message volume.</li>
 * </ul>
 *
 * <p><b>Must never throw into the caller</b> — this runs after the consumer already ACKed the
 * message; any failure here is swallowed and logged, never allowed to affect ACK/NAK semantics.
 */
@Service
public class ConnectionActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ConnectionActivityRecorder.class);

    /** At most one DB write per connection per this window, regardless of message volume. */
    static final Duration WRITE_THROTTLE = Duration.ofSeconds(15);

    private final ConnectionRepository connectionRepository;
    private final ConnectionActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    private final ConcurrentHashMap<ScopeKindKey, Long> connectionIdCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> lastWriteAtByConnection = new ConcurrentHashMap<>();

    public ConnectionActivityRecorder(
            ConnectionRepository connectionRepository,
            ConnectionActivityRepository activityRepository,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            TransactionTemplate transactionTemplate) {
        this.connectionRepository = connectionRepository;
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Record that an event was processed for {@code (workspaceId, kind)}. No-ops if there is no
     * ACTIVE connection for that pair, or if the last write for the resolved connection is still
     * within {@link #WRITE_THROTTLE}. Never throws.
     */
    public void recordEventProcessed(long workspaceId, IntegrationKind kind, String eventType) {
        try {
            transactionTemplate.executeWithoutResult(status -> doRecord(workspaceId, kind, eventType));
        } catch (Exception e) {
            log.warn(
                    "Failed to record connection activity: workspaceId={}, kind={}, eventType={}",
                    workspaceId,
                    kind,
                    sanitizeForLog(eventType),
                    e);
        }
    }

    /**
     * Evicts the {@code (workspaceId, kind) -> connectionId} cache entry, forcing the next {@link
     * #recordEventProcessed} call to re-resolve via {@link ConnectionRepository}. Callers should
     * invoke this whenever a workspace's connection of this kind changes (reconnect, deactivation).
     */
    public void invalidate(long workspaceId, IntegrationKind kind) {
        connectionIdCache.remove(new ScopeKindKey(workspaceId, kind));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(ConnectionLifecycleEvent.Activated event) {
        invalidate(event.workspaceId(), event.kind());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeactivated(ConnectionLifecycleEvent.Deactivated event) {
        invalidate(event.workspaceId(), event.kind());
    }

    private void doRecord(long workspaceId, IntegrationKind kind, String eventType) {
        Long connectionId = resolveConnectionId(workspaceId, kind);
        if (connectionId == null) {
            return;
        }
        Instant now = clock.instant();
        if (!claimWriteSlot(connectionId, now)) {
            return;
        }
        try {
            activityRepository.upsertActivity(connectionId, workspaceId, now, eventType);
        } catch (RuntimeException e) {
            // Release the slot we just claimed so a failed write doesn't throttle the retry for the full
            // window — otherwise a transient DB blip would blank liveness until the next event 15s later.
            releaseWriteSlot(connectionId, now);
            throw e;
        }
        eventPublisher.publishEvent(
                new SyncStateChangedEvent(workspaceId, connectionId, kind, SyncStateChangedEvent.Scope.ACTIVITY));
    }

    private @Nullable Long resolveConnectionId(long workspaceId, IntegrationKind kind) {
        ScopeKindKey key = new ScopeKindKey(workspaceId, kind);
        Long cached = connectionIdCache.get(key);
        if (cached != null) {
            return cached;
        }
        return connectionRepository
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(workspaceId, kind, IntegrationState.ACTIVE)
                .map(Connection::getId)
                .map(id -> {
                    connectionIdCache.put(key, id);
                    return id;
                })
                .orElse(null);
    }

    /**
     * Atomically claims the write slot for {@code connectionId} if the last write is outside {@link
     * #WRITE_THROTTLE} (or there was none). Uses {@link ConcurrentHashMap#compute} so two concurrent
     * callers for the same connection can't both observe a stale timestamp and both write.
     */
    private boolean claimWriteSlot(Long connectionId, Instant now) {
        AtomicReference<Boolean> claimed = new AtomicReference<>(Boolean.FALSE);
        lastWriteAtByConnection.compute(connectionId, (id, lastWriteAt) -> {
            if (lastWriteAt != null && Duration.between(lastWriteAt, now).compareTo(WRITE_THROTTLE) < 0) {
                claimed.set(Boolean.FALSE);
                return lastWriteAt;
            }
            claimed.set(Boolean.TRUE);
            return now;
        });
        return Boolean.TRUE.equals(claimed.get());
    }

    /** Undo a claim whose write failed — only if no later write already moved the slot past {@code claimedAt}. */
    private void releaseWriteSlot(Long connectionId, Instant claimedAt) {
        lastWriteAtByConnection.compute(connectionId, (id, current) -> claimedAt.equals(current) ? null : current);
    }

    private record ScopeKindKey(long workspaceId, IntegrationKind kind) {}
}
