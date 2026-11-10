package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/** Commit-time pool hints. A dedicated connection listens without holding a connection from the application pool. */
@Component
@ConditionalOnAgentRuntime
class LlmPoolNotifications implements DisposableBean {
    static final String CHANNEL = "llm_call_pool";
    private static final Logger log = LoggerFactory.getLogger(LlmPoolNotifications.class);
    private static final int WAIT_MILLIS = 10_000;

    private final DataSourceProperties dataSource;
    private volatile boolean running = true;
    private @Nullable Thread thread;

    LlmPoolNotifications(DataSourceProperties dataSource) {
        this.dataSource = dataSource;
    }

    synchronized void subscribe(LongConsumer changed, Runnable resync) {
        if (thread != null || !running) return;
        thread = Thread.ofPlatform().name("llm-pool-listener").daemon(true).start(() -> listen(changed, resync));
    }

    private void listen(LongConsumer changed, Runnable resync) {
        long backoffMillis = 1000;
        while (running) {
            try (var connection = DriverManager.getConnection(
                            dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword());
                    var statement = connection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
                PGConnection notifications = connection.unwrap(PGConnection.class);
                backoffMillis = 1000;
                // A reconnect missed hints. Waiters read committed rows again, regardless of the payloads lost.
                resync.run();
                while (running) {
                    var received = notifications.getNotifications(WAIT_MILLIS);
                    if (received != null) {
                        for (var notification : received) {
                            try {
                                long poolId = Long.parseLong(notification.getParameter());
                                if (poolId > 0) changed.accept(poolId);
                            } catch (NumberFormatException malformed) {
                                log.warn("Ignoring a model pool notification with an invalid pool id");
                            }
                        }
                    }
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException failed) {
                if (!running) return;
                log.warn(
                        "Model pool notifications disconnected: error={}",
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
