package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.HostSandbox;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The worker's one Heph host and its command channel ({@code docs/contributor/unified-pi-runtime.mdx} § The Heph
 * host). The host's WebSocket client answers each ping without host code, so a host whose process is stuck stops
 * answering, and one that leaves {@code UNANSWERED_PINGS} pings in a row unanswered is gone, like one whose channel
 * closed.
 */
public class HephHost {

    private static final Logger log = LoggerFactory.getLogger(HephHost.class);

    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;
    private static final int SEND_BUFFER_BYTES = 1024 * 1024;

    /** How often a starting host is checked for having exited. */
    private static final Duration LIVENESS_CHECK = Duration.ofSeconds(2);

    /** The dispatcher pings at every tick, so this is a few ticks of a host that answers nothing. */
    static final int UNANSWERED_PINGS = 6;

    private final HostSandbox sandbox;
    private final Object lock = new Object();
    private volatile Runnable lost = () -> {};

    /** Guarded by {@link #lock}. */
    private HostSandbox.@Nullable Host host;

    /** Guarded by {@link #lock}: the channel as the host opened it. */
    private @Nullable WebSocketSession socket;

    /** The thread-safe sender over {@link #socket}; read without the lock. */
    private volatile @Nullable WebSocketSession channel;

    /** Pings sent since the host last answered one. */
    private final AtomicInteger unanswered = new AtomicInteger();

    /** Guarded by {@link #lock}; set once the worker shuts its host down, so no host starts after it. */
    private boolean closed;

    public HephHost(HostSandbox sandbox) {
        this.sandbox = sandbox;
    }

    /** Called when the running host is gone, so its turns stop counting against this worker. */
    void onLost(Runnable lost) {
        this.lost = lost;
    }

    /**
     * Waits until a host's command channel is open, starting a host when none runs. Only the dispatcher's claim thread
     * calls it. The launch runs outside the lock, so the channel handshake and a shutdown never wait for Docker.
     *
     * @return false when no host could start or connect in time, or the host exited before it connected
     */
    boolean awaitReady(Duration timeout) {
        HostSandbox.Host launched;
        synchronized (lock) {
            if (channel != null) {
                return true;
            }
            launched = host;
        }
        if (launched == null) {
            HostSandbox.Host starting = new HostSandbox.Host(UUID.randomUUID());
            synchronized (lock) {
                if (closed) {
                    return false;
                }
                // Current before the launch returns: the process connects as soon as it runs, and the launch returns
                // only once the backend has seen it run. A host that a later launch replaced is not current, and its
                // channel is refused.
                host = starting;
            }
            try {
                launched = sandbox.launch(starting.id(), HephHostSpec.spec());
            } catch (IOException e) {
                // The class only: the sandbox logs a cause it knows, such as a worker container it cannot identify.
                log.warn("Could not start the Heph host: {}", e.getClass().getSimpleName());
                forget(starting);
                return false;
            } catch (RuntimeException e) {
                log.warn("Could not start the Heph host: {}", e.getClass().getSimpleName(), e);
                forget(starting);
                return false;
            }
            boolean shutDown;
            synchronized (lock) {
                shutDown = closed || !launched.equals(host);
            }
            if (shutDown) {
                sandbox.remove(launched);
                return false;
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        String failure = "did not open its command channel within " + timeout;
        while (true) {
            synchronized (lock) {
                if (channel != null) {
                    return true;
                }
                long remaining = deadline - System.nanoTime();
                if (!launched.equals(host) || remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, Math.min(remaining, LIVENESS_CHECK.toNanos()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (channel != null) {
                    return true;
                }
            }
            if (!sandbox.isRunning(launched)) {
                failure = "exited before it opened its command channel";
                break;
            }
        }
        synchronized (lock) {
            if (launched.equals(host)) {
                host = null;
            }
        }
        log.warn("The Heph host {}; removing it", failure);
        sandbox.remove(launched);
        return false;
    }

    /** Whether the sandbox runtime starts a host now; a running host also needs it for the sandboxes of its turns. */
    boolean sandboxReady() {
        return sandbox.ready();
    }

    /**
     * Stops counting a host whose launch failed as the current one, with the channel it may have opened before the
     * launch failed: a ready channel of a removed host would take the next turn.
     */
    private void forget(HostSandbox.Host starting) {
        WebSocketSession early;
        synchronized (lock) {
            if (!starting.equals(host)) {
                return;
            }
            early = socket;
            host = null;
            socket = null;
            channel = null;
        }
        if (early != null) {
            try {
                early.close();
            } catch (IOException e) {
                log.debug(
                        "Could not close the channel of a Heph host that did not start: {}",
                        e.getClass().getSimpleName());
            }
        }
    }

    boolean isReady() {
        return channel != null;
    }

    /** Whether {@code hostId} is the host that runs now, the only one whose command channel is accepted. */
    boolean isCurrent(UUID hostId) {
        synchronized (lock) {
            return host != null && host.id().equals(hostId);
        }
    }

    /** Accepts the command channel of the running host, once. */
    boolean connected(UUID hostId, WebSocketSession session) {
        synchronized (lock) {
            if (host == null || !host.id().equals(hostId) || socket != null) {
                return false;
            }
            socket = session;
            channel = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
            unanswered.set(0);
            lock.notifyAll();
        }
        log.info("Heph host {} is ready", hostId);
        return true;
    }

    /** The host's command channel closed: the host is gone. */
    void disconnected(WebSocketSession session) {
        HostSandbox.Host gone;
        synchronized (lock) {
            if (socket == null || !socket.getId().equals(session.getId())) {
                return;
            }
            gone = host;
            host = null;
            socket = null;
            channel = null;
        }
        log.warn("The Heph host closed its command channel; its running turns are left to their leases");
        lost.run();
        if (gone != null) {
            HostSandbox.Host removed = gone;
            Thread.ofPlatform().name("heph-host-removal").daemon(true).start(() -> sandbox.remove(removed));
        }
    }

    /**
     * Pings the host's channel, or closes it as gone when the host left {@link #UNANSWERED_PINGS} pings unanswered or
     * the ping cannot be sent. Never throws.
     */
    void ping() {
        WebSocketSession sender = channel;
        if (sender == null) {
            return;
        }
        String failure = null;
        if (unanswered.getAndIncrement() >= UNANSWERED_PINGS) {
            failure = "answered none of the last " + UNANSWERED_PINGS + " pings";
        } else {
            try {
                sender.sendMessage(new PingMessage());
            } catch (IOException | RuntimeException e) {
                failure = "could not be pinged: " + e.getClass().getSimpleName();
            }
        }
        if (failure == null) {
            return;
        }
        log.warn("The Heph host {}; closing its command channel", failure);
        WebSocketSession open;
        synchronized (lock) {
            open = socket;
        }
        if (open == null) {
            return;
        }
        try {
            open.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException | RuntimeException e) {
            log.debug(
                    "Could not close the Heph host command channel: {}",
                    e.getClass().getSimpleName());
        }
        // The close callback reports this too; whichever comes second finds the host gone already.
        disconnected(open);
    }

    void ponged(WebSocketSession session) {
        synchronized (lock) {
            if (socket != null && socket.getId().equals(session.getId())) {
                unanswered.set(0);
            }
        }
    }

    void send(String frame) throws IOException {
        WebSocketSession sender = channel;
        if (sender == null || !sender.isOpen()) {
            throw new IOException("No Heph host is connected");
        }
        sender.sendMessage(new TextMessage(frame));
    }

    void shutdown() {
        HostSandbox.Host gone;
        WebSocketSession open;
        synchronized (lock) {
            closed = true;
            gone = host;
            open = socket;
            host = null;
            socket = null;
            channel = null;
        }
        if (open != null) {
            try {
                open.close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.debug(
                        "Could not close the Heph host command channel: {}",
                        e.getClass().getSimpleName());
            }
        }
        if (gone != null) {
            sandbox.remove(gone);
        }
    }
}
