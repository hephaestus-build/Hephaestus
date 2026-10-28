package de.tum.cit.aet.hephaestus.mentor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Carries ledger changes between processes over PostgreSQL {@code LISTEN/NOTIFY}. Every {@link MentorLedgerEvents}
 * event becomes a notification on {@link #CHANNEL} inside the transaction that published it, so PostgreSQL delivers
 * it only when that transaction commits. The payload is the event itself; a listener reads the rows.
 *
 * <p>The first {@link #subscribe} opens one dedicated connection outside the pool and keeps it for the life of the
 * process. Notifications sent while that connection is down are lost, so after every (re)connect each listener is
 * told to {@link Listener#resync} and read the state it cares about again.
 */
@Component
public class MentorLedgerNotifications implements DisposableBean {

    public static final String CHANNEL = "mentor_ledger";

    private static final Logger log = LoggerFactory.getLogger(MentorLedgerNotifications.class);

    /** How long one wait for notifications blocks; each wait ends with a query that proves the connection alive. */
    private static final int WAIT_MILLIS = 10_000;

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** Receives the ledger changes of every process. Called on the listener thread, so it must return quickly. */
    public interface Listener {
        void turnChanged(MentorLedgerEvents.TurnChanged change);

        default void entryChanged(MentorLedgerEvents.EntryChanged change) {}

        default void readChanged(MentorLedgerEvents.ReadChanged change) {}

        default void contextChanged(MentorLedgerEvents.ContextChanged change) {}

        /** Changes made while the connection was down were missed: read the current state again. */
        default void resync() {}
    }

    private final MentorTurnRepository turns;
    private final DataSourceProperties dataSource;
    private final ObjectMapper mapper;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile @Nullable Thread thread;
    private volatile boolean running = true;

    public MentorLedgerNotifications(MentorTurnRepository turns, DataSourceProperties dataSource, ObjectMapper mapper) {
        this.turns = turns;
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void notify(MentorLedgerEvents.LedgerChanged change) {
        turns.notifyLedger(
                CHANNEL,
                mapper.writerFor(MentorLedgerEvents.LedgerChanged.class).writeValueAsString(change));
    }

    /** Starts listening on first use. The listener stays registered until the process stops. */
    public void subscribe(Listener listener) {
        listeners.add(listener);
        synchronized (this) {
            if (thread == null && running) {
                thread = Thread.ofPlatform()
                        .name("mentor-ledger-listener")
                        .daemon(true)
                        .start(this::listen);
            }
        }
    }

    private void listen() {
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try (Connection connection = DriverManager.getConnection(
                            dataSource.determineUrl(), dataSource.determineUsername(), dataSource.determinePassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
                PGConnection notifications = connection.unwrap(PGConnection.class);
                backoff = Duration.ofSeconds(1);
                listeners.forEach(MentorLedgerNotifications::resync);
                while (running) {
                    PGNotification[] received = notifications.getNotifications(WAIT_MILLIS);
                    if (received != null) {
                        for (PGNotification notification : received) {
                            dispatch(notification.getParameter());
                        }
                    }
                    statement.execute("SELECT 1");
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn(
                        "Hephaestus ledger notifications lost their connection; reconnecting in {}: {}",
                        backoff,
                        e.getClass().getSimpleName());
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    private void dispatch(String payload) {
        MentorLedgerEvents.LedgerChanged change;
        try {
            change = mapper.readValue(payload, MentorLedgerEvents.LedgerChanged.class);
        } catch (JacksonException malformed) {
            // The class only: a parser message can quote the notification's payload.
            log.warn(
                    "Ignoring a malformed Hephaestus ledger notification: {}",
                    malformed.getClass().getSimpleName());
            return;
        }
        for (Listener listener : listeners) {
            switch (change) {
                case MentorLedgerEvents.TurnChanged turn -> deliver(() -> listener.turnChanged(turn));
                case MentorLedgerEvents.EntryChanged entry -> deliver(() -> listener.entryChanged(entry));
                case MentorLedgerEvents.ReadChanged read -> deliver(() -> listener.readChanged(read));
                case MentorLedgerEvents.ContextChanged context -> deliver(() -> listener.contextChanged(context));
            }
        }
    }

    private static void resync(Listener listener) {
        deliver(listener::resync);
    }

    private static void deliver(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException e) {
            log.warn("A Hephaestus ledger listener failed", e);
        }
    }

    @Override
    public void destroy() {
        running = false;
        Thread listening = thread;
        if (listening != null) {
            listening.interrupt();
        }
    }
}
