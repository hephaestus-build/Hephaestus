package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Wakes the idle claimers when a provider event is stored. PostgreSQL delivers {@link ProviderEventStore#CHANNEL} after
 * the storing transaction commits, also from another server. A dedicated connection listens, so no connection of the
 * application pool waits. A notification can be lost on reconnect, so the claimers also poll.
 */
@Component
@ConditionalOnServerRole
public class ProviderEventNotifications implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventNotifications.class);
    private static final int WAIT_MILLIS = 10_000;

    private final DataSourceProperties dataSource;
    private volatile boolean running = true;
    private @Nullable Thread thread;

    ProviderEventNotifications(DataSourceProperties dataSource) {
        this.dataSource = dataSource;
    }

    /** Calls {@code stored} on the listener's thread for each notification from now on. */
    public synchronized void subscribe(Runnable stored) {
        if (thread != null || !running) return;
        thread =
                Thread.ofPlatform().name("provider-event-listener").daemon(true).start(() -> listen(stored));
    }

    private void listen(Runnable stored) {
        long backoffMillis = 1000;
        while (running) {
            try (var connection = DriverManager.getConnection(
                            dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword());
                    var statement = connection.createStatement()) {
                statement.execute("LISTEN " + ProviderEventStore.CHANNEL);
                PGConnection notifications = connection.unwrap(PGConnection.class);
                backoffMillis = 1000;
                while (running) {
                    var received = notifications.getNotifications(WAIT_MILLIS);
                    if (received != null && received.length > 0) {
                        stored.run();
                    }
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException failed) {
                if (!running) return;
                log.warn(
                        "Provider event notifications disconnected: error={}",
                        failed.getClass().getSimpleName());
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMillis = Math.min(30_000, backoffMillis * 2);
            }
        }
    }

    @Override
    public synchronized void destroy() {
        running = false;
        Thread listening = thread;
        if (listening != null) listening.interrupt();
    }
}
