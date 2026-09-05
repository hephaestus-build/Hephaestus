package de.tum.cit.aet.hephaestus.agent.sandbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Backend-neutral sandbox capacity, lifecycle and workspace transfer limits. */
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
            defaultResourceLimits = new DefaultResourceLimits(4L * 1024 * 1024 * 1024, 2.0, 256);
        }
    }

    /**
     * @param memoryBytes maximum memory in bytes, including tmpfs
     */
    public record DefaultResourceLimits(
            @DefaultValue("4294967296") @Min(1) long memoryBytes,
            @DefaultValue("2.0") @DecimalMin("0.01") double cpus,
            @DefaultValue("256") @Min(1) int pidsLimit) {}
}
