package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends bounded sync invalidations over PostgreSQL LISTEN/NOTIFY. Every replica, including the origin, delivers only
 * received notifications to its local SSE emitters. Missed hints are harmless: clients also poll REST.
 *
 * <p>Like the ledger and permit listeners, this listener owns one connection outside the application pool. Its
 * reconnect and dispatch cannot stall the listeners that enforce leases or grant permits.
 */
@Component
@ConditionalOnServerRole
public class SyncPushService {

    static final String CHANNEL = "sync_status";
    static final String APPLICATION_NAME = "hephaestus-sync-push";
    private static final int WAIT_MILLIS = 10_000;
    private static final int MAX_PAYLOAD_LENGTH = 128;
    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    private record Notification(long workspaceId, long connectionId, SyncStateChangedEvent.Scope scope) {}

    private final SyncEventHub hub;
    private final ObjectMapper mapper;
    private final DataSourceProperties dataSource;
    private final SyncJobRepository syncJobs;
    private final String listenerName = APPLICATION_NAME + "-" + UUID.randomUUID();
    private final Counter publishSuccess;
    private final Counter publishFailure;
    private final Counter receiveSuccess;
    private final Counter receiveFailure;
    private volatile boolean running = true;
    private @Nullable Thread thread;
    private @Nullable Connection listening;

    public SyncPushService(
            SyncEventHub hub,
            ObjectMapper mapper,
            DataSourceProperties dataSource,
            SyncJobRepository syncJobs,
            MeterRegistry meters) {
        this.hub = hub;
        this.mapper = mapper;
        this.dataSource = dataSource;
        this.syncJobs = syncJobs;
        publishSuccess = counter(meters, "postgres_publish", "success");
        publishFailure = counter(meters, "postgres_publish", "failure");
        receiveSuccess = counter(meters, "postgres_receive", "success");
        receiveFailure = counter(meters, "postgres_receive", "failure");
    }

    /** Identifies this physical listener in PostgreSQL, including when this bean is proxied. */
    String listenerName() {
        return listenerName;
    }

    @PostConstruct
    synchronized void startListening() {
        if (thread == null && running) {
            thread = Thread.ofPlatform()
                    .name("sync-status-listener")
                    .daemon(true)
                    .start(this::listen);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSyncStateChanged(SyncStateChangedEvent event) {
        publish(new Notification(event.workspaceId(), event.connectionId(), event.scope()));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnectionActivated(ConnectionLifecycleEvent.Activated event) {
        publish(new Notification(event.workspaceId(), event.connectionId(), SyncStateChangedEvent.Scope.CONNECTION));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnectionDeactivated(ConnectionLifecycleEvent.Deactivated event) {
        publish(new Notification(event.workspaceId(), event.connectionId(), SyncStateChangedEvent.Scope.CONNECTION));
    }

    private void publish(Notification notification) {
        try {
            syncJobs.notifySyncStatus(CHANNEL, mapper.writeValueAsString(notification));
            publishSuccess.increment();
        } catch (DataAccessException | TransactionException failed) {
            publishFailure.increment();
            // A failed hint must not replay delivery locally: the database might have accepted it before failing.
            log.warn(
                    "Sync notification publish failed: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    private void listen() {
        long backoffMillis = 1000;
        while (running) {
            try (var connection = DriverManager.getConnection(
                            dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword());
                    var statement = connection.createStatement()) {
                connection.setClientInfo("ApplicationName", listenerName);
                statement.execute("LISTEN " + CHANNEL);
                synchronized (this) {
                    if (!running) return;
                    listening = connection;
                }
                PGConnection notifications = connection.unwrap(PGConnection.class);
                backoffMillis = 1000;
                while (running) {
                    var received = notifications.getNotifications(WAIT_MILLIS);
                    if (received != null) {
                        for (var notification : received) onNotification(notification.getParameter());
                    }
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException failed) {
                if (!running) return;
                receiveFailure.increment();
                log.warn(
                        "Sync notifications disconnected: error={}",
                        failed.getClass().getSimpleName());
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMillis = Math.min(30_000, backoffMillis * 2);
            } finally {
                synchronized (this) {
                    listening = null;
                }
            }
        }
    }

    void onNotification(String payload) {
        try {
            if (payload.length() > MAX_PAYLOAD_LENGTH) throw new IllegalArgumentException("Oversized hint");
            Notification notification = mapper.readValue(payload, Notification.class);
            if (notification == null
                    || notification.workspaceId() <= 0
                    || notification.connectionId() <= 0
                    || notification.scope() == null) throw new IllegalArgumentException("Invalid routing hint");
            hub.publish(
                    notification.workspaceId(),
                    new SyncEventHint(notification.scope().wireValue(), notification.connectionId()));
            receiveSuccess.increment();
        } catch (JacksonException | IllegalArgumentException failed) {
            receiveFailure.increment();
            log.warn(
                    "Ignoring an invalid sync notification: error={}",
                    failed.getClass().getSimpleName());
        }
    }

    private static Counter counter(MeterRegistry meters, String transport, String outcome) {
        return Counter.builder(IntegrationCoreMetrics.INTEGRATION_SYNC_PUSH_MESSAGES)
                .description("Sync invalidation messages by transport boundary and outcome")
                .tag("transport", transport)
                .tag("outcome", outcome)
                .register(meters);
    }

    @PreDestroy
    synchronized void shutdown() {
        running = false;
        if (thread != null) thread.interrupt();
        if (listening != null) {
            try {
                listening.close();
            } catch (SQLException failed) {
                log.warn("Sync notification close failed", failed);
            }
        }
    }
}
