package de.tum.cit.aet.hephaestus.agent.conversation.web;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A live stream's emitter that tells whether Spring installed its response handler. A send before that waits in Spring's
 * own buffer, and Spring flushes the buffer when it installs the handler: past every fence of the hub. So the hub sends
 * nothing until the handler is installed.
 */
class ConversationEventEmitter extends SseEmitter {

    /** How often a writer looks again while the request thread installs the handler. */
    private static final long INSTALL_POLL_MS = 5;

    /**
     * Spring's handler of the emitter. Spring exposes no hook after it installs it; it sets the field under
     * {@link #writeLock}, which a subclass may hold, so a read under that lock sees it.
     */
    private static final VarHandle HANDLER;

    static {
        try {
            HANDLER = MethodHandles.privateLookupIn(ResponseBodyEmitter.class, MethodHandles.lookup())
                    .unreflectVarHandle(ResponseBodyEmitter.class.getDeclaredField("handler"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    ConversationEventEmitter(long timeoutMillis) {
        super(timeoutMillis);
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
