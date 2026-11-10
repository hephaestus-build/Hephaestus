package de.tum.cit.aet.hephaestus.agent.adapter;

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
 * Wakes each server's evidence reconcile when an erasure or a purge commits. PostgreSQL delivers {@link #CHANNEL} after
 * the erasing transaction commits. A dedicated connection listens, so no connection of the application pool waits. A
 * wake-up can be lost on reconnect; the reconcile's own timer bounds the delay then, so a lost one never matters.
 */
@Component
public class EvidenceErasureNotifications implements DisposableBean {

    static final String CHANNEL = "evidence_erasure";
    private static final Logger log = LoggerFactory.getLogger(EvidenceErasureNotifications.class);
    private static final int WAIT_MILLIS = 10_000;

    private final DataSourceProperties dataSource;
    private volatile boolean running = true;
    private @Nullable Thread thread;

    EvidenceErasureNotifications(DataSourceProperties dataSource) {
        this.dataSource = dataSource;
    }

    /** Calls {@code erased} after each erasure that commits from now on, on the listener's thread. */
    public synchronized void subscribe(Runnable erased) {
        if (thread != null || !running) return;
        thread = Thread.ofPlatform()
                .name("evidence-erasure-listener")
                .daemon(true)
                .start(() -> listen(erased));
    }

    private void listen(Runnable erased) {
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
                    if (received != null && received.length > 0) erased.run();
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException failed) {
                if (!running) return;
                log.warn(
                        "Evidence erasure notifications disconnected: error={}",
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
