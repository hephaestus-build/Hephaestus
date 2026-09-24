package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridges {@link SyncStateChangedEvent} to {@link SyncEventHub} — the only consumer of that event
 * today. Multi-replica fan-out uses a plain (non-JetStream) NATS subject,
 * {@code hephaestus.syncstatus.<workspaceId>}: at-most-once is fine here, clients poll the REST
 * surface as a fallback if a hint is dropped.
 *
 * <p>The shared NATS {@link Connection} bean may be entirely ABSENT (feature disabled,
 * {@code hephaestus.sync.nats.enabled=false} — no bean method registered at all) or present but
 * {@code null} ({@code NatsConfig#natsConnection} explicitly returns {@code null} under the
 * {@code specs} profile). {@link ObjectProvider#getIfAvailable()} handles both: empty provider and
 * a null bean instance both come back as {@code null} here.
 *
 * <p>When NATS is available, a workspace-changed event is published to NATS ONLY — never fanned out
 * to the local {@link SyncEventHub} directly from this listener. Delivery to local emitters happens
 * exclusively via {@link #onMessage} on the {@code hephaestus.syncstatus.>} subscription, which every
 * replica (including the origin) receives exactly once. Publishing AND locally delivering here would
 * double-deliver on the origin replica.
 */
@Component
@ConditionalOnServerRole
public class SyncPushService {

    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    private static final String SUBJECT_PREFIX = "hephaestus.syncstatus.";
    private static final String WILDCARD_SUBJECT = SUBJECT_PREFIX + ">";

    private final SyncEventHub hub;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Connection> natsConnectionProvider;
    private final Counter localDelivery;
    private final Counter natsPublishSuccess;
    private final Counter natsPublishFailure;
    private final Counter natsReceiveSuccess;
    private final Counter natsReceiveFailure;
    private volatile @Nullable Dispatcher dispatcher;

    public SyncPushService(
            SyncEventHub hub,
            ObjectMapper objectMapper,
            @Qualifier("natsConnection") ObjectProvider<Connection> natsConnectionProvider,
            MeterRegistry meterRegistry) {
        this.hub = hub;
        this.objectMapper = objectMapper;
        this.natsConnectionProvider = natsConnectionProvider;
        this.localDelivery = counter(meterRegistry, "local", "success");
        this.natsPublishSuccess = counter(meterRegistry, "nats_publish", "success");
        this.natsPublishFailure = counter(meterRegistry, "nats_publish", "failure");
        this.natsReceiveSuccess = counter(meterRegistry, "nats_receive", "success");
        this.natsReceiveFailure = counter(meterRegistry, "nats_receive", "failure");
    }

    /**
     * Subscribes once, after construction: the {@link Connection} bean (when present and non-null) is
     * already connected by the time Spring finishes constructing it (its {@code @Bean} method blocks
     * on {@code Nats.connect}), so there is no readiness race to wait out here.
     */
    @jakarta.annotation.PostConstruct
    void subscribeIfNatsAvailable() {
        Connection connection = natsConnectionProvider.getIfAvailable();
        if (connection == null) {
            log.info("Sync push: NATS unavailable, using in-process delivery only");
            return;
        }
        dispatcher = connection.createDispatcher(this::onMessage);
        dispatcher.subscribe(WILDCARD_SUBJECT);
        log.info("Sync push: subscribed to {}", WILDCARD_SUBJECT);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSyncStateChanged(SyncStateChangedEvent event) {
        SyncEventHint hint = new SyncEventHint(event.scope().wireValue(), event.connectionId());
        deliver(event.workspaceId(), hint);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnectionActivated(ConnectionLifecycleEvent.Activated event) {
        deliver(
                event.workspaceId(),
                new SyncEventHint(SyncStateChangedEvent.Scope.CONNECTION.wireValue(), event.connectionId()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnectionDeactivated(ConnectionLifecycleEvent.Deactivated event) {
        deliver(
                event.workspaceId(),
                new SyncEventHint(SyncStateChangedEvent.Scope.CONNECTION.wireValue(), event.connectionId()));
    }

    private void deliver(long workspaceId, SyncEventHint hint) {
        Connection connection = natsConnectionProvider.getIfAvailable();
        if (connection == null) {
            deliverLocally(workspaceId, hint);
            return;
        }
        publishToNats(connection, workspaceId, hint);
    }

    private void publishToNats(Connection connection, long workspaceId, SyncEventHint hint) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(hint);
            connection.publish(SUBJECT_PREFIX + workspaceId, payload);
            natsPublishSuccess.increment();
        } catch (Exception e) {
            natsPublishFailure.increment();
            log.warn("Sync push: NATS publish failed for workspaceId={}: {}", workspaceId, e.toString());
            // The origin can still update immediately. Duplicate invalidations are harmless if NATS
            // accepted the publish before throwing; polling remains the cross-replica safety net.
            deliverLocally(workspaceId, hint);
        }
    }

    /** Package-private so unit tests can drive an inbound message without a live NATS broker. */
    void onMessage(Message message) {
        String subject = message.getSubject();
        try {
            long workspaceId = parseWorkspaceId(subject);
            SyncEventHint hint = objectMapper.readValue(message.getData(), SyncEventHint.class);
            hub.publish(workspaceId, hint);
            natsReceiveSuccess.increment();
        } catch (Exception e) {
            natsReceiveFailure.increment();
            log.warn("Sync push: failed to handle inbound message on subject '{}': {}", subject, e.toString());
        }
    }

    private void deliverLocally(long workspaceId, SyncEventHint hint) {
        hub.publish(workspaceId, hint);
        localDelivery.increment();
    }

    private static Counter counter(MeterRegistry meterRegistry, String transport, String outcome) {
        return Counter.builder(IntegrationCoreMetrics.INTEGRATION_SYNC_PUSH_MESSAGES)
                .description("Sync invalidation messages by transport boundary and outcome")
                .tag("transport", transport)
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private static long parseWorkspaceId(String subject) {
        if (!subject.startsWith(SUBJECT_PREFIX)) {
            throw new IllegalArgumentException("Unexpected subject: " + subject);
        }
        return Long.parseLong(subject.substring(SUBJECT_PREFIX.length()));
    }

    @PreDestroy
    void shutdown() {
        Dispatcher current = dispatcher;
        if (current != null) {
            try {
                current.unsubscribe(WILDCARD_SUBJECT);
            } catch (Exception e) {
                log.debug("Sync push: failed to unsubscribe cleanly: {}", e.toString());
            }
        }
    }
}
