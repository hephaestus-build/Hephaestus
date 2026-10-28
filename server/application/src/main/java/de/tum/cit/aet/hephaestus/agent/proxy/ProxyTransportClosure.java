package de.tum.cit.aet.hephaestus.agent.proxy;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import reactor.netty.Connection;

/** A cancelled subscription is not a closed transport. Capacity remains held until its channel closes. */
final class ProxyTransportClosure {
    private final Set<Connection> closing = new HashSet<>();
    private @Nullable Runnable release;

    Request begin() {
        return new Request();
    }

    /** Registration and completion share a lock, so a delayed connect cannot dispatch after release. */
    final class Request {
        private @Nullable Connection connection;
        private boolean finished;

        synchronized void connected(Connection opened) {
            if (finished) {
                opened.dispose();
                throw new IllegalStateException("The model request ended before its transport connected");
            }
            connection = opened;
        }

        synchronized void finish(boolean completed) {
            if (finished) return;
            finished = true;
            if (!completed && connection != null) closing(connection);
        }
    }

    void closing(Connection connection) {
        synchronized (this) {
            closing.add(connection);
        }
        connection.channel().closeFuture().addListener(unused -> {
            // A database release must not block Netty's event loop.
            Thread.ofVirtual().name("llm-transport-closed").start(() -> closed(connection));
        });
        connection.dispose();
    }

    private void closed(Connection connection) {
        Runnable ready;
        synchronized (this) {
            closing.remove(connection);
            ready = closing.isEmpty() ? release : null;
            if (ready != null) release = null;
        }
        if (ready != null) ready.run();
    }

    void releaseWhenClosed(Runnable action) {
        synchronized (this) {
            if (!closing.isEmpty()) {
                release = action;
                return;
            }
        }
        action.run();
    }
}
