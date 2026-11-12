package de.tum.cit.aet.hephaestus.agent.job;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for agent jobs: the {@code review_job} rows of practice reviews, which review sandboxes run, and the
 * retention of their rows and payloads.
 *
 * @param enabled           Turns on agent jobs, which run practice reviews, on this server.
 * @param drainTimeout      How long shutdown waits for leased review runs, with the gateway still available.
 * @param payloadRetention  age from {@code completed_at} at which a TERMINAL job's {@code capture}
 *                          and {@code output} are stripped to NULL
 * @param rowRetention      Age, counted from the end of a job, at which an ended job row that waits for no delivery
 *                          is deleted. Must be at least the payload retention.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.agent")
public record AgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("PT30M") @NotNull Duration drainTimeout,

        @DefaultValue("P14D") @NotNull Duration payloadRetention,
        @DefaultValue("P90D") @NotNull Duration rowRetention) {
    /** Bean Validation has no duration-comparison constraint, so the {@link Duration} bounds are checked here. */
    public AgentProperties {
        if (drainTimeout == null || drainTimeout.isNegative()) {
            throw new IllegalArgumentException("hephaestus.agent.drain-timeout must not be negative");
        }
        if (payloadRetention == null || payloadRetention.isNegative() || payloadRetention.isZero()) {
            throw new IllegalArgumentException(
                    "hephaestus.agent.payload-retention must be positive, got: " + payloadRetention);
        }
        if (rowRetention == null || rowRetention.compareTo(payloadRetention) < 0) {
            throw new IllegalArgumentException("hephaestus.agent.row-retention (" + rowRetention
                    + ") must be >= payload-retention ("
                    + payloadRetention
                    + ") — a row cannot be deleted before its payload would already have been stripped");
        }
    }
}
