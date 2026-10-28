package de.tum.cit.aet.hephaestus.agent.proxy;

import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import reactor.netty.Connection;

/** A cancelled subscription is not a closed transport. Capacity remains held until its channel closes. */
final class ProxyTransportClosure {
    private final Set<Connection> closing = new HashSet<>();
    private @Nullable Runnable release;

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
