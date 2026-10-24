package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Complete specification for a sandboxed practice review attempt.
 *
 * <p>Domain-agnostic — the sandbox manager never interprets the contents of the inputs or of the result. The inputs
 * fill the sandbox's {@code /workspace} through the worker's gateway; the result comes back the same way, from
 * {@code SandboxLayout.OUTPUT_PATH}.
 *
 * @param attempt the claimed job's attempt count; null when the caller holds no claim on the job
 * @param gatewayToken the credential that the sandbox presents to the gateway, its only destination
 * @param resourceLimits CPU, memory, PID, and timeout constraints
 * @param workDeadline when the work must end, if earlier than {@code resourceLimits.maxRuntime()} from the start
 * @param inputFiles files to place in /workspace (relative path → content)
 * @param inputFilesOnDisk inputs staged by host path and streamed into the sandbox, never read into this process
 * @param inputDirectories captured evidence directories, streamed into the sandbox the same way
 */
public record SandboxSpec(
        UUID jobId,
        @Nullable Integer attempt,
        String image,
        List<String> command,
        Map<String, String> environment,
        String gatewayToken,
        ResourceLimits resourceLimits,
        @Nullable Instant workDeadline,
        Map<String, byte[]> inputFiles,
        Map<String, Path> inputFilesOnDisk,
        List<EvidenceDirectory> inputDirectories) {
    public SandboxSpec {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(image, "image must not be null");
        Objects.requireNonNull(gatewayToken, "gatewayToken must not be null");
        Objects.requireNonNull(resourceLimits, "resourceLimits must not be null");
        if (attempt != null && attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        if (image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        if (gatewayToken.isBlank()) {
            throw new IllegalArgumentException("Gateway credential required");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        inputFiles = Map.copyOf(inputFiles);
        inputFilesOnDisk = Map.copyOf(inputFilesOnDisk);
        inputDirectories = List.copyOf(inputDirectories);
    }
}
