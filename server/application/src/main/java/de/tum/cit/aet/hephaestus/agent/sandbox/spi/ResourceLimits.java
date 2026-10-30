package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** CPU time and the work deadline. Kubernetes sizes memory, disk, and process isolation by Pod kind. */
public record ResourceLimits(double cpus, @Nullable Duration maxRuntime) {
    /** {@link #MAX_CPUS} as configuration validation reads it. */
    public static final String MAX_CPUS_TEXT = "8.0";

    public static final double MAX_CPUS = Double.parseDouble(MAX_CPUS_TEXT);

    public static final Duration MAX_RUNTIME = Duration.ofHours(3);

    public ResourceLimits {
        if (cpus <= 0) {
            throw new IllegalArgumentException("cpus must be positive, got: " + cpus);
        }
        if (cpus > MAX_CPUS) {
            throw new IllegalArgumentException("cpus exceeds maximum (" + MAX_CPUS + "), got: " + cpus);
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
