package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Micrometer instruments for the interactive sandbox. Stable names — do not rename without
 * dashboard coordination. Bounded cardinality: no per-session/user/workspace labels.
 */
public final class InteractiveSandboxMetrics {

    final Counter attachFailureFirstFrameTimeout;
    final Counter attachFailureFirstFrameFailed;
    final Counter attachFailureMaxSessions;
    final Counter attachFailureOther;

    final Timer attachDuration;

    final Counter sendBytes;
    final Counter recvBytes;

    final Counter sendRejectedQueueFull;
    final Counter sendRejectedWriteTimeout;
    final Counter sendRejectedBrokenPipe;
    final Counter sendRejectedClosed;

    final Counter ringBufferDropped;

    final Counter subscriberDropped;
    final Counter subscriberError;

    final Counter frameParseError;

    final Timer lifetime;
    final DistributionSummary subscribersAtClose;

    private final Map<EvictionReason, Counter> evictionsByReason;

    public InteractiveSandboxMetrics(MeterRegistry registry) {
        // _timeout = runner went silent; _failed = pump/writer terminated before any frame.
        this.attachFailureFirstFrameTimeout = attachFailure(registry, "first_frame_timeout");
        this.attachFailureFirstFrameFailed = attachFailure(registry, "first_frame_failed");
        this.attachFailureMaxSessions = attachFailure(registry, "max_sessions");
        this.attachFailureOther = attachFailure(registry, "other");

        this.attachDuration = Timer.builder(AgentMetrics.MENTOR_ATTACH_DURATION)
                .description("Time from container spawn to first JSONL frame received from runner")
                .register(registry);

        this.sendBytes = Counter.builder(AgentMetrics.MENTOR_SEND_FRAME_BYTES)
                .tag("direction", "in")
                .description("Bytes sent to runner stdin (UTF-8 encoded JSONL including the newline terminator)")
                .register(registry);
        this.recvBytes = Counter.builder(AgentMetrics.MENTOR_SEND_FRAME_BYTES)
                .tag("direction", "out")
                .description("Bytes received from runner stdout (UTF-8 encoded JSONL line, no terminator)")
                .register(registry);

        this.sendRejectedQueueFull = sendRejected(registry, "queue_full");
        this.sendRejectedWriteTimeout = sendRejected(registry, "write_timeout");
        this.sendRejectedBrokenPipe = sendRejected(registry, "broken_pipe");
        this.sendRejectedClosed = sendRejected(registry, "closed");

        this.ringBufferDropped = Counter.builder(AgentMetrics.MENTOR_RING_BUFFER_DROPPED)
                .description("Frames evicted from the per-session ring buffer (drop-oldest on overflow)")
                .register(registry);

        this.subscriberDropped = Counter.builder(AgentMetrics.MENTOR_SUBSCRIBER_DROPPED)
                .tag("reason", "queue_full")
                .description("Frames dropped from a subscriber's bounded queue due to slow listener")
                .register(registry);
        this.subscriberError = Counter.builder(AgentMetrics.MENTOR_SUBSCRIBER_ERROR)
                .description("Subscriber listener invocations that threw")
                .register(registry);

        this.frameParseError = Counter.builder(AgentMetrics.MENTOR_FRAME_PARSE_ERROR)
                .description("JSONL frames that failed to parse (skipped, session continues)")
                .register(registry);

        // Per-session counters / timers / gauges share the mentor.session.* stem.
        this.lifetime = Timer.builder(AgentMetrics.MENTOR_SESSION_LIFETIME)
                .description("End-to-end session lifetime (attach to close)")
                .register(registry);

        this.subscribersAtClose = DistributionSummary.builder(AgentMetrics.MENTOR_SESSION_SUBSCRIBERS_AT_CLOSE)
                .description("Subscriber count observed at session close")
                .register(registry);

        this.evictionsByReason = new EnumMap<>(EvictionReason.class);
        for (EvictionReason r : EvictionReason.values()) {
            evictionsByReason.put(
                    r,
                    Counter.builder(AgentMetrics.MENTOR_SESSION_EVICTION)
                            .tag("reason", r.tag())
                            .description("Session evictions by reason (covers all termination causes)")
                            .register(registry));
        }
    }

    Counter evictionsBy(EvictionReason reason) {
        return Objects.requireNonNull(evictionsByReason.get(reason));
    }

    private static Counter attachFailure(MeterRegistry registry, String reason) {
        return Counter.builder(AgentMetrics.MENTOR_ATTACH_FAILURE)
                .tag("reason", reason)
                .description("attach() failures by reason")
                .register(registry);
    }

    private static Counter sendRejected(MeterRegistry registry, String reason) {
        return Counter.builder(AgentMetrics.MENTOR_SEND_REJECTED)
                .tag("reason", reason)
                .description("send() rejections by reason")
                .register(registry);
    }
}
