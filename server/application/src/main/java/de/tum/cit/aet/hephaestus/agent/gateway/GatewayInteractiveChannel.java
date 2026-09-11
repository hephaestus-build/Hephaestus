package de.tum.cit.aet.hephaestus.agent.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Bridges the existing bounded JSONL pumps to the gateway's authenticated WebSocket. */
public final class GatewayInteractiveChannel implements AutoCloseable {
    private final Pipe commands;
    private final Pipe frames;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxFrameBytes;
    private final int writeTimeoutMs;
    private @Nullable WebSocketSession connection;

    public GatewayInteractiveChannel(int maxFrameBytes, int writeTimeoutMs) throws IOException {
        if (maxFrameBytes <= 0 || writeTimeoutMs <= 0) {
            throw new IllegalArgumentException("Interactive transport limits must be positive");
        }
        commands = Pipe.open();
        try {
            frames = Pipe.open();
        } catch (IOException exception) {
            commands.source().close();
            commands.sink().close();
            throw exception;
        }
        this.maxFrameBytes = maxFrameBytes;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public Reader stdout() {
        return Channels.newReader(frames.source(), StandardCharsets.UTF_8);
    }

    public OutputStream stdin() {
        return Channels.newOutputStream(commands.sink());
    }

    public synchronized boolean connect(WebSocketSession socket) {
        if (closed.get() || connection != null) {
            return false;
        }
        socket.setTextMessageSizeLimit(maxFrameBytes);
        var sending = new ConcurrentWebSocketSessionDecorator(socket, writeTimeoutMs, maxFrameBytes);
        connection = sending;
        Thread.ofVirtual().name("sandbox-gateway-commands-" + socket.getId()).start(() -> {
            try (var reader = new BufferedReader(Channels.newReader(commands.source(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sending.sendMessage(new TextMessage(line));
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
        var output = Channels.newOutputStream(frames.sink());
        output.write(bytes);
        output.write('\n');
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
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
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
        for (var channel : java.util.List.of(commands.source(), commands.sink(), frames.sink())) {
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
