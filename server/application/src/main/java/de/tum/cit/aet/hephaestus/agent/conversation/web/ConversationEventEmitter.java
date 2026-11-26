package de.tum.cit.aet.hephaestus.agent.conversation.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.catalina.connector.Response;
import org.apache.catalina.connector.ResponseFacade;
import org.apache.coyote.AbstractProcessor;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.WebUtils;

/**
 * A live stream's emitter that tells whether Spring installed its response handler. A send before that waits in Spring's
 * own buffer, and Spring flushes the buffer when it installs the handler: past every fence of the hub. So the hub sends
 * nothing until the handler is installed.
 */
class ConversationEventEmitter extends SseEmitter {

    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final long WRITE_DEADLINE_MS = 1_000L;

    /** How often a writer looks again while the request thread installs the handler. */
    private static final long INSTALL_POLL_MS = 5;

    /**
     * Spring's handler of the emitter. Spring exposes no hook after it installs it; it sets the field under
     * {@link #writeLock}, which a subclass may hold, so a read under that lock sees it.
     */
    private static final VarHandle HANDLER = field(ResponseBodyEmitter.class, "handler");

    // Servlet completion can flush a partial response. Capture the actual Tomcat connection so a timeout can abort it.
    private static final VarHandle TOMCAT_RESPONSE = field(ResponseFacade.class, "response");
    private static final VarHandle PROCESSOR = field(org.apache.coyote.Response.class, "hook");
    private static final VarHandle SOCKET = field(AbstractProcessor.class, "socketWrapper");

    private final SocketWrapperBase<?> socket;
    private final SocketChannel connection;
    private volatile boolean aborted;

    private static VarHandle field(Class<?> owner, String name) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                    .unreflectVarHandle(owner.getDeclaredField(name));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    ConversationEventEmitter(HttpServletResponse response) {
        super(STREAM_TIMEOUT_MS);
        var facade = Objects.requireNonNull(WebUtils.getNativeResponse(response, ResponseFacade.class));
        var nativeResponse = (Response) TOMCAT_RESPONSE.get(facade);
        var processor = (AbstractProcessor) PROCESSOR.get(nativeResponse.getCoyoteResponse());
        socket = (SocketWrapperBase<?>) SOCKET.get(processor);
        connection = Objects.requireNonNull(((NioChannel) socket.getSocket()).getIOChannel());
    }

    @Override
    public void send(Set<DataWithMediaType> frame) throws IOException {
        var write = new FutureTask<Void>(() -> {
            super.send(frame);
            return null;
        });
        Thread.ofVirtual().name("conversation-sse-write").start(write);
        try {
            write.get(WRITE_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw abort(write, new SocketTimeoutException("The live stream write timed out"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw abort(write, new InterruptedIOException("The live stream writer was interrupted"));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException failure) throw failure;
            if (failed.getCause() instanceof IOException failure) throw failure;
            throw new IOException(failed.getCause());
        }
    }

    private IOException abort(FutureTask<Void> write, IOException failure) {
        aborted = true;
        // The caller still holds the history lock. Abort before cancelling the task so it cannot resume the frame.
        try {
            try {
                connection.setOption(StandardSocketOptions.SO_LINGER, 0);
            } finally {
                connection.close();
            }
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        write.cancel(true);
        socket.setError(failure);
        socket.processSocket(SocketEvent.ERROR, true);
        return failure;
    }

    @Override
    public void complete() {
        if (!aborted) super.complete();
    }

    /** Whether Spring installed the response handler, so a send goes to the client and not to Spring's buffer. */
    boolean installed() {
        writeLock.lock();
        try {
            return HANDLER.get(this) != null;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Waits on the calling writer's thread until the handler is installed: the request thread installs it right after
     * the stream opens.
     *
     * @return {@code false} when it was not installed within {@code max}, or the thread was interrupted
     */
    boolean awaitInstalled(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        while (!installed()) {
            if (System.nanoTime() - deadline > 0) return false;
            try {
                Thread.sleep(INSTALL_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
