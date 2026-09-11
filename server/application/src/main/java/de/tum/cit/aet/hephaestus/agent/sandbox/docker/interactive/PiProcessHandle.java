package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.gateway.GatewayInteractiveChannel;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.time.Duration;

final class PiProcessHandle {
    private final GatewayInteractiveChannel channel;

    PiProcessHandle(GatewayInteractiveChannel channel) {
        this.channel = channel;
    }

    Reader stdout() {
        return channel.stdout();
    }

    OutputStream stdin() {
        return channel.stdin();
    }

    boolean isAlive() {
        return channel.exitValueOrAlive() == -1;
    }

    boolean waitFor(Duration timeout) {
        return channel.waitFor(timeout);
    }

    int exitValueOrAlive() {
        return channel.exitValueOrAlive();
    }

    void destroyForcibly() {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    void awaitExitAndClose(Duration timeout) {
        channel.waitFor(timeout);
        destroyForcibly();
    }
}
