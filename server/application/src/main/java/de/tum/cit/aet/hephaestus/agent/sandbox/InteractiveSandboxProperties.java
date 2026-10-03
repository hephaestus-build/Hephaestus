package de.tum.cit.aet.hephaestus.agent.sandbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resource tuning for the interactive (mentor) sandbox. Bound from {@code hephaestus.mentor.*}.
 *
 * @param idleTtlSeconds time since the last frame before an idle runner is stopped. It trades a warm
 *     runner's memory, dominated by Pi SDK imports that cannot be slimmed, against a full cold start on the
 *     next message; the session caps, not the TTL, bound the total. The Traefik sticky-cookie
 *     {@code maxAge} in {@code docker/compose.app.yaml} follows this value.
 * @param graceTimeoutSeconds SIGTERM → SIGKILL grace. Capped at 25 s: the registry's
 *     {@code @PreDestroy} adds a 5-second slop, and Spring's default
 *     {@code spring.lifecycle.timeout-per-shutdown-phase} is 30 s. A grace beyond 25 s would
 *     overshoot the phase and leak containers on shutdown.
 * @param sendQueueCapacity bounded writer queue. {@code send()} rejects when full — the only
 *     honest backpressure signal to upstream callers (a timeout alone allows unbounded queueing).
 * @param maxFrameBytes runner-frame budget in UTF-8 bytes, enforced by the gateway and the worker relay.
 *     Capped at 1 MiB so a frame and its hub envelope fit the control transport's 2 MiB limit. An oversized
 *     frame ends the session instead of consuming unbounded memory.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.mentor")
public record InteractiveSandboxProperties(
        @DefaultValue("900") @Min(1) int idleTtlSeconds,
        @DefaultValue("25") @Min(1) @Max(25) int graceTimeoutSeconds,
        @DefaultValue("30") @Min(1) int reapIntervalSeconds,
        @DefaultValue("512") @Min(16) int ringBufferFrames,
        @DefaultValue("5000") @Min(100) int stdinWriteTimeoutMs,
        @DefaultValue("64") @Min(1) int sendQueueCapacity,
        @DefaultValue("64") @Min(1) int subscriberQueueCapacity,
        @DefaultValue("30") @Min(1) int attachFirstFrameTimeoutSeconds,
        @DefaultValue("3") @Min(1) int maxSessionsPerUser,
        @DefaultValue("50") @Min(1) int maxSessionsTotal,

        @DefaultValue("1048576") @Min(1024) @Max(MAX_FRAME_BYTES)
        int maxFrameBytes) {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
}
