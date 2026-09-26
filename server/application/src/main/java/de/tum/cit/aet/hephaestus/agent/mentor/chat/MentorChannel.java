package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.exception.ClientDisconnectedException;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import org.springframework.modulith.NamedInterface;

/**
 * Transport-neutral sink for exactly <em>one</em> mentor turn. One instance == one turn; never reuse
 * across turns. {@link MentorChatService} drives the whole turn through this interface and must not
 * branch on the concrete transport.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link MentorSseChannel} — the webapp surface over an HTTP {@code text/event-stream}.</li>
 *   <li>Slack DM channel — renders {@link UIMessageChunk}s through Slack's streaming API inside an assistant thread.</li>
 * </ul>
 *
 * <h3>Contract every implementation must honour</h3>
 * <ol>
 *   <li><b>Write serialization.</b> {@link #send} is invoked from both the turn thread and the Pi
 *       event-callback thread. Implementations MUST serialize writes internally; callers never
 *       synchronize.</li>
 *   <li><b>Post-terminal no-op.</b> After any {@code completeWith*} or {@link #close()}, every method
 *       is a silent no-op. A stray late {@link #send} (e.g. a runner emitting a second terminal
 *       event) must not be mistaken for a client disconnect.</li>
 *   <li><b>Idempotent terminals.</b> All {@code completeWith*} methods and {@link #close()} are
 *       idempotent and never throw.</li>
 * </ol>
 */
@NamedInterface(name = "mentor-chat", propagate = true)
public interface MentorChannel extends AutoCloseable {
    enum DeliveryOutcome {
        DELIVERED,
        INSTANCE_SILENCED,
        NOT_DELIVERED,
    }

    /**
     * Register the "the far side went away" hook, fired <em>exactly once</em> on the first transition
     * to disconnected (SSE: client closed the stream; Slack: a stream write fails because the user
     * deleted the message). Runs immediately on the calling thread if already disconnected. Transports
     * with no disconnect notion simply never fire it.
     */
    void onDisconnect(Runnable hook);

    /**
     * Cheap pre-flight guard the orchestrator polls to abort before paying for sandbox attach / a Pi
     * handshake. Returns {@code false} for transports that cannot observe a disconnect (e.g. a Slack
     * DM has no live socket to lose).
     */
    boolean isClientGone();

    /**
     * Begin whatever liveness signal this transport needs for the duration of a long turn. SSE
     * schedules comment-only pings so idle proxies do not close the stream; a Slack adapter re-issues
     * its "thinking…" status under Slack's ~120&nbsp;s auto-clear. Permitted to be a no-op. Idempotent.
     */
    void startKeepAlive();

    /**
     * Emit one AI-SDK chunk. Serialized (see contract). No-op after a terminal.
     *
     * @throws ClientDisconnectedException if the transport detects the peer is gone, so the
     *     orchestrator can stop writing without poisoning the runner subscription.
     */
    void send(UIMessageChunk chunk);

    /** Natural terminal: flush, close, and report what reached the transport. Idempotent. */
    DeliveryOutcome completeWithDone();

    /** Error terminal: surface {@code errorText} to the user, then finalize. Idempotent. */
    void completeWithError(String errorText);

    /** 409 terminal: a turn is already in flight for this thread. Idempotent. */
    void completeWithConflict();

    /** End the transport if no terminal did, and release its resources. Idempotent. Never throws. */
    @Override
    void close();
}
