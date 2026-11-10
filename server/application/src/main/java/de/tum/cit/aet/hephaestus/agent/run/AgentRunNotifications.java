package de.tum.cit.aet.hephaestus.agent.run;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Wakes a claimer when a run is queued. PostgreSQL delivers {@link #CHANNEL} after the queuing transaction commits. A
 * dedicated connection listens, so no connection of the application pool waits. A hint can be lost on reconnect, so a
 * claimer also polls.
 */
@Component
public class AgentRunNotifications implements DisposableBean {

    static final String CHANNEL = "agent_run";
    private static final Logger log = LoggerFactory.getLogger(AgentRunNotifications.class);
    private static final int WAIT_MILLIS = 10_000;

    private final DataSourceProperties dataSource;
    private volatile boolean running = true;
    private @Nullable Thread thread;
    private final List<Consumer<AgentRunKind>> subscribers = new CopyOnWriteArrayList<>();

    AgentRunNotifications(DataSourceProperties dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Calls {@code queued} with the kind of each run queued from now on, on the one listener's thread: every subscriber
     * hears every run, in the order they subscribed. A subscriber must return at once.
     */
    public synchronized void subscribe(Consumer<AgentRunKind> queued) {
        if (!running) return;
        subscribers.add(queued);
        if (thread == null)
            thread = Thread.ofPlatform().name("agent-run-listener").daemon(true).start(this::listen);
    }

    /**
     * Hands {@code kind} to every subscriber. A subscriber that throws is logged, and the others still hear the run: one
     * failure must not cost the listener its connection.
     */
    static void publish(List<Consumer<AgentRunKind>> subscribers, AgentRunKind kind) {
        for (Consumer<AgentRunKind> subscriber : subscribers) {
            try {
                subscriber.accept(kind);
            } catch (RuntimeException failed) {
                log.warn(
                        "A run notification subscriber failed: kind={}, error={}",
                        kind,
                        failed.getClass().getSimpleName());
            }
        }
    }

    private void listen() {
        long backoffMillis = 1000;
        while (running) {
            try (var connection = DriverManager.getConnection(
                            dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword());
                    var statement = connection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
                PGConnection notifications = connection.unwrap(PGConnection.class);
                backoffMillis = 1000;
                while (running) {
                    var received = notifications.getNotifications(WAIT_MILLIS);
                    if (received != null) {
                        for (var notification : received) {
                            AgentRunKind kind;
                            try {
                                kind = AgentRunKind.valueOf(notification.getParameter());
                            } catch (IllegalArgumentException unknown) {
                                log.warn("Ignoring a run notification of an unknown kind");
                                continue;
                            }
                            publish(subscribers, kind);
                        }
                    }
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException failed) {
                if (!running) return;
                log.warn(
                        "Run notifications disconnected: error={}",
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
