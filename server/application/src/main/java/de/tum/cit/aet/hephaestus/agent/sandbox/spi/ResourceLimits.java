package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Resource constraints for a sandboxed container.
 *
 * <p>{@code memoryBytes} includes tmpfs allocations — set it high enough to accommodate both
 * process RSS and tmpfs mounts.
 *
 * @param memoryBytes maximum memory in bytes (container + tmpfs combined)
 * @param cpus CPU limit (e.g. 2.0 = two full cores)
 * @param pidsLimit maximum number of processes inside the container
 * @param maxRuntime work deadline; the batch runner can use the result-upload grace after it
 */
public record ResourceLimits(
        long memoryBytes,
        double cpus,
        int pidsLimit,
        @Nullable Duration maxRuntime) {
    public static final long MAX_MEMORY_BYTES = 16L * 1024 * 1024 * 1024;

    /** {@link #MAX_CPUS} as configuration validation reads it. */
    public static final String MAX_CPUS_TEXT = "8.0";

    public static final double MAX_CPUS = Double.parseDouble(MAX_CPUS_TEXT);

    public static final int MAX_PIDS = 4096;

    public static final Duration MAX_RUNTIME = Duration.ofHours(3);

    public ResourceLimits {
        if (memoryBytes <= 0) {
            throw new IllegalArgumentException("memoryBytes must be positive, got: " + memoryBytes);
        }
        if (memoryBytes > MAX_MEMORY_BYTES) {
            throw new IllegalArgumentException(
                    "memoryBytes exceeds maximum (" + MAX_MEMORY_BYTES + "), got: " + memoryBytes);
        }
        if (cpus <= 0) {
            throw new IllegalArgumentException("cpus must be positive, got: " + cpus);
        }
        if (cpus > MAX_CPUS) {
            throw new IllegalArgumentException("cpus exceeds maximum (" + MAX_CPUS + "), got: " + cpus);
        }
        if (pidsLimit <= 0) {
            throw new IllegalArgumentException("pidsLimit must be positive, got: " + pidsLimit);
        }
        if (pidsLimit > MAX_PIDS) {
            throw new IllegalArgumentException("pidsLimit exceeds maximum (" + MAX_PIDS + "), got: " + pidsLimit);
        }
        Objects.requireNonNull(maxRuntime, "maxRuntime must not be null");
        if (maxRuntime.isNegative() || maxRuntime.isZero()) {
            throw new IllegalArgumentException("maxRuntime must be positive, got: " + maxRuntime);
        }
        if (maxRuntime.compareTo(MAX_RUNTIME) > 0) {
            throw new IllegalArgumentException("maxRuntime exceeds maximum (" + MAX_RUNTIME + "), got: " + maxRuntime);
        }
    }

    @Override
    public Duration maxRuntime() {
        return Objects.requireNonNull(maxRuntime);
    }
}
