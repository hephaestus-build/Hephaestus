package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Renewal and cancellation bounds. An expired active permit remains counted until its transport closes.
 *
 * @param permitLease Unused reservation renewal window. A live worker cancels calls after three quarters of this window
 *                    without renewal. Active grants remain counted until transport closure is confirmed.
 * @param maxCooldown Longest shared provider pause accepted from an instance-funded HTTP 429 response.
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
}
