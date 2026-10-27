package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Backend-neutral sandbox capacity and lifecycle settings. Bound from {@code
 * hephaestus.sandbox.*} in {@code application.yml}. Docker-specific settings live in {@code
 * DockerSandboxProperties} under {@code hephaestus.sandbox.docker}.
 *
 * @param maxConcurrentContainers upper bound on simultaneous sandbox containers
 * @param containerStopTimeoutSeconds SIGTERM → SIGKILL grace period
 * @param reconciliationIntervalSeconds interval between orphan cleanup sweeps
 * @param defaultResourceLimits the limits of each review sandbox; the Heph host and turn sandboxes have their own. On
 *     Kubernetes, the backend sizes the memory and disk of each sandbox Pod itself ({@code KubernetesPods})
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.sandbox")
public record SandboxProperties(
        @DefaultValue("5") @Min(1) int maxConcurrentContainers,
        @DefaultValue("10") @Min(1) int containerStopTimeoutSeconds,
        @DefaultValue("60") @Min(10) int reconciliationIntervalSeconds,
        @DefaultValue @Valid DefaultResourceLimits defaultResourceLimits) {

    /** The memory a review sandbox keeps for its processes when every in-memory file system it has is full. */
    public static final long MIN_PROCESS_BYTES = 512L * 1024 * 1024;

    /**
     * Refuses a review memory limit that full in-memory file systems could exhaust: the kernel would then kill the
     * review for files the agent wrote, not for what its processes use.
     *
     * @param inMemoryBytes the most that the backend's in-memory file systems of a review sandbox hold
     */
    public static void requireRoomBeside(long memoryBytes, long inMemoryBytes) {
        long floor = inMemoryBytes + MIN_PROCESS_BYTES;
        if (memoryBytes < floor) {
            throw new IllegalStateException(
                    "SANDBOX_MEMORY_BYTES is " + memoryBytes + "; a review sandbox needs at least "
                            + floor + ": " + inMemoryBytes + " for its in-memory file systems and " + MIN_PROCESS_BYTES
                            + " for its processes");
        }
    }

    /**
     * The resource limits of a review sandbox: its initializer and then its runtime. The defaults and the review usage
     * they rest on are in docs/admin/sizing.mdx § Review sandbox limits.
     *
     * @param memoryBytes maximum memory in bytes on Docker, tmpfs pages included. Kubernetes sizes each sandbox Pod by its kind
     * @param cpus CPU limit: a share of CPU time the sandbox may use, not a reservation
     * @param pidsLimit maximum process count
     */
    public record DefaultResourceLimits(
            @DefaultValue("3221225472") @Min(1) @Max(ResourceLimits.MAX_MEMORY_BYTES)
            long memoryBytes,

            @DefaultValue("1.0") @DecimalMin("0.01") @DecimalMax(ResourceLimits.MAX_CPUS_TEXT)
            double cpus,

            @DefaultValue("256") @Min(1) @Max(ResourceLimits.MAX_PIDS)
            int pidsLimit) {

        /** The limits of one review attempt, which runs until its work deadline. */
        public ResourceLimits forAttempt(Duration maxRuntime) {
            return new ResourceLimits(memoryBytes, cpus, pidsLimit, maxRuntime);
        }
    }
}
