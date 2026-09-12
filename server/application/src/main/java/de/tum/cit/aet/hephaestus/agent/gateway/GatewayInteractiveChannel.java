package de.tum.cit.aet.hephaestus.agent.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Bridges the bounded JSONL pumps to the gateway's authenticated WebSocket. One virtual thread is the
 * only sender on the socket, so the session needs no concurrent-send decorator.
 */
public final class GatewayInteractiveChannel implements AutoCloseable {
    private final Pipe commands;
    private final Pipe frames;
    private final Reader stdout;
    private final OutputStream stdin;
    private final OutputStream received;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxFrameBytes;
    private @Nullable WebSocketSession connection;

    public GatewayInteractiveChannel(int maxFrameBytes) throws IOException {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("Interactive frame limit must be positive");
        }
        commands = Pipe.open();
        try {
            frames = Pipe.open();
        } catch (IOException exception) {
            commands.source().close();
            commands.sink().close();
            throw exception;
        }
        stdout = Channels.newReader(frames.source(), StandardCharsets.UTF_8);
        stdin = Channels.newOutputStream(commands.sink());
        received = Channels.newOutputStream(frames.sink());
        this.maxFrameBytes = maxFrameBytes;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public Reader stdout() {
        return stdout;
    }

    public OutputStream stdin() {
        return stdin;
    }

    public synchronized boolean connect(WebSocketSession socket) {
        if (closed.get() || connection != null) {
            return false;
        }
        socket.setTextMessageSizeLimit(maxFrameBytes);
        connection = socket;
        Thread.ofVirtual().name("sandbox-gateway-commands-" + socket.getId()).start(() -> {
            try (var reader = new BufferedReader(Channels.newReader(commands.source(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    socket.sendMessage(new TextMessage(line));
                }
            } catch (IOException | RuntimeException exception) {
                finish(1);
            }
        });
        return true;
    }

    public void receive(TextMessage message) throws IOException {
        byte[] bytes = message.asBytes();
        if (closed.get() || bytes.length > maxFrameBytes) {
            throw new IOException("Interactive frame rejected");
        }
        received.write(bytes);
        received.write('\n');
    }

    public void finish(int code) {
        exit.complete(code);
        try {
            closeTransport();
        } catch (IOException ignored) {
        }
    }

    public int exitValueOrAlive() {
        return exit.getNow(-1);
    }

    public boolean waitFor(Duration timeout) {
        try {
            exit.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            closeTransport();
        } finally {
            frames.source().close();
        }
    }

    private void closeTransport() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exit.complete(1);
        IOException failure = null;
        for (var channel : List.of(commands.source(), commands.sink(), frames.sink())) {
            try {
                channel.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        WebSocketSession socket;
        synchronized (this) {
            socket = connection;
        }
        if (socket != null && socket.isOpen()) {
            try {
                socket.close(CloseStatus.NORMAL);
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
