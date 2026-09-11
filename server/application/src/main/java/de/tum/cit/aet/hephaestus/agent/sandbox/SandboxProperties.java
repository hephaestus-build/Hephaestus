package de.tum.cit.aet.hephaestus.agent.sandbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Backend-neutral sandbox capacity, lifecycle and workspace transfer limits. Bound from {@code
 * hephaestus.sandbox.*} in {@code application.yml}. Docker-specific settings live in {@code
 * DockerSandboxProperties} under {@code hephaestus.sandbox.docker}.
 *
 * @param maxConcurrentContainers upper bound on simultaneous sandbox containers
 * @param containerStopTimeoutSeconds SIGTERM → SIGKILL grace period
 * @param reconciliationIntervalSeconds interval between orphan cleanup sweeps
 * @param maxDirectoryBytes maximum total bytes for directory injection via tar (default 1 GB)
 * @param maxDirectoryEntries maximum entry count for directory injection (default 500,000)
 * @param defaultResourceLimits default resource constraints for containers
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.sandbox")
public record SandboxProperties(
        @DefaultValue("5") @Min(1) int maxConcurrentContainers,
        @DefaultValue("10") @Min(1) int containerStopTimeoutSeconds,
        @DefaultValue("60") @Min(10) int reconciliationIntervalSeconds,
        @DefaultValue("1073741824") @Min(1) long maxDirectoryBytes,
        @DefaultValue("500000") @Min(1) int maxDirectoryEntries,
        @Nullable @Valid DefaultResourceLimits defaultResourceLimits) {
    public SandboxProperties {
        if (defaultResourceLimits == null) {
            defaultResourceLimits = new DefaultResourceLimits(4L * 1024 * 1024 * 1024, 2.0, 512);
        }
    }

    /**
     * Default resource limits for agent containers.
     *
     * @param memoryBytes maximum memory in bytes (includes tmpfs)
     * @param cpus CPU limit
     * @param pidsLimit maximum process count
     */
    public record DefaultResourceLimits(
            @DefaultValue("4294967296") @Min(1) long memoryBytes,
            @DefaultValue("2.0") @DecimalMin("0.01") double cpus,
            @DefaultValue("512") @Min(1) int pidsLimit) {}
}
