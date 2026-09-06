package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.time.Duration;
import java.util.Map;

/**
 * Result of a sandboxed container execution.
 *
 * @param exitCode container exit code (0 = success, 137 = OOM-killed, etc.)
 * @param outputFiles files collected from the container's output path (relative path → content)
 * @param logs container stdout and stderr, captured before removal; whole unless the run outgrew what
 *     the collector holds, in which case it names how much of its middle is missing
 * @param timedOut whether the container was killed due to exceeding {@link
 *     ResourceLimits#maxRuntime()}
 * @param duration wall-clock execution time (container start to exit)
 */
public record SandboxResult(
        int exitCode, Map<String, byte[]> outputFiles, String logs, boolean timedOut, Duration duration) {}
