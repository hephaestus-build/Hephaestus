package de.tum.cit.aet.hephaestus.agent.sandbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Resource tuning for the interactive (mentor) sandbox. Bound from {@code hephaestus.mentor.*}.
 *
 * @param idleTtlSeconds default 900 s (15 min) since the last frame. A mentor runner is ~165 MB RSS,
 *     a floor dominated by Pi SDK imports that we cannot slim (transitive imports through
 *     {@code core/resource-loader} always pull in the interactive theme + highlight.js), so the TTL
 *     trades idle memory for warm turns; the session caps, not the TTL, bound the total. A message
 *     after it pays a full cold start — job network, volumes, workspace initializer, runtime
 *     container, {@code runner_ready} and the SDK import — which runs to several seconds; opening Heph
 *     starts that ahead of the message. The Traefik sticky-cookie {@code maxAge} in
 *     {@code docker/compose.app.yaml} follows this value.
 * @param graceTimeoutSeconds SIGTERM → SIGKILL grace. Capped at 25 s: the registry's
 *     {@code @PreDestroy} adds a 5-second slop, and Spring's default
 *     {@code spring.lifecycle.timeout-per-shutdown-phase} is 30 s. A grace beyond 25 s would
 *     overshoot the phase and leak containers on shutdown.
 * @param sendQueueCapacity bounded writer queue. {@code send()} rejects when full — the only
 *     honest backpressure signal to upstream callers (a timeout alone allows unbounded queueing).
 * @param maxFrameChars upper bound on a single stdout-line length (chars, not bytes — a UTF-8
 *     encoded character can be up to 4 bytes, so the on-wire memory ceiling is roughly 4× this).
 *     A longer line is treated as a stream-level fault and the session terminates {@code ERROR}.
 *     Without this bound a hostile runner could OOM the app-server.
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
        @DefaultValue("1048576") @Min(1024) int maxFrameChars) {}
