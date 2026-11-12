package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Server sandbox capacity and lifecycle settings under {@code hephaestus.sandbox}.
 * Kubernetes Pod kinds own their memory, disk, and process-isolation bounds.
 *
 * @param maxConcurrentContainers the most review sandboxes this server runs at once
 * @param defaultResourceLimits the CPU limit of each review sandbox
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.sandbox")
public record SandboxProperties(
        @DefaultValue("5") @Min(1) int maxConcurrentContainers,
        @DefaultValue @Valid DefaultResourceLimits defaultResourceLimits) {

    /**
     * The configurable CPU limit of a review. Memory and disk come from its Kubernetes Pod kind.
     *
     * @param cpus the CPU limit in cores of each review sandbox
     */
    public record DefaultResourceLimits(
            @DefaultValue("1.0") @DecimalMin("0.01") @DecimalMax(ResourceLimits.MAX_CPUS_TEXT)
            double cpus) {

        /** The limits of one review attempt, which runs until its work deadline. */
        public ResourceLimits forAttempt(Duration maxRuntime) {
            return new ResourceLimits(cpus, maxRuntime);
        }
    }
}
