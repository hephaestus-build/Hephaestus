package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How workers hold the permits of a {@link LlmCallPool}, bound from {@code hephaestus.llm.call-pools.*}.
 *
 * <p>One lease sets the three intervals, so they cannot contradict each other. A worker renews its permits every
 * quarter lease. A worker that saw no successful renewal for three quarters of a lease cancels its calls in the pool
 * itself. The database counts a permit that no worker renewed for a lease and a quarter as free. The half lease between
 * the two covers a pause, a slow cancellation and the difference of the two clocks. A longer stall can overshoot the
 * limit: the provider checks no permit of ours.
 *
 * @param permitLease how long a permit counts without a renewal. A shorter lease frees a crashed worker's capacity
 *     sooner and renews more often
 * @param maxCooldown the longest pause after a rate limit answer, whatever the provider's {@code Retry-After} asks for
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.llm.call-pools")
public record LlmCallPoolProperties(
        @DefaultValue("PT1M")
        @NotNull
        @DurationMin(
                seconds = 20,
                message = "hephaestus.llm.call-pools.permit-lease (HEPHAESTUS_LLM_CALL_POOLS_PERMIT_LEASE) must be"
                        + " at least 20 seconds")
        Duration permitLease,

        @DefaultValue("PT5M")
        @NotNull
        @DurationMin(
                seconds = 1,
                message = "hephaestus.llm.call-pools.max-cooldown (HEPHAESTUS_LLM_CALL_POOLS_MAX_COOLDOWN) must be"
                        + " at least 1 second")
        Duration maxCooldown) {

    /** How often a worker renews its permits. */
    public Duration renewEvery() {
        return permitLease.dividedBy(4);
    }

    /** How long a worker holds its calls without a successful renewal before it cancels them. */
    public Duration selfFenceAfter() {
        return permitLease.minus(renewEvery());
    }

    /** How long the database counts a permit that no worker renews. */
    public Duration reclaimAfter() {
        return permitLease.plus(renewEvery());
    }
}
