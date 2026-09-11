package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Complete specification for a sandboxed container execution.
 *
 * <p>Domain-agnostic — the sandbox manager never interprets the contents of {@code inputFiles} or
 * the data written to {@code outputPath}. Handlers and adapters produce a {@code SandboxSpec}; the
 * sandbox manager executes it.
 *
 * <p>Files are injected into the container workspace before execution and collected from the
 * output path after completion. The transfer mechanism is an implementation detail of the
 * underlying {@link SandboxManager}.
 *
 * @param networkPolicy network access and LLM proxy configuration
 * @param resourceLimits CPU, memory, PID, and timeout constraints
 * @param inputFiles files to inject into /workspace (relative path → content)
 * @param outputPath container path to collect results from after execution
 * @param volumeMounts host bind mounts (host path → container path); mounted read-only
 */
public record SandboxSpec(
        UUID jobId,
        String image,
        List<String> command,
        Map<String, String> environment,
        @Nullable NetworkPolicy networkPolicy,
        ResourceLimits resourceLimits,
        @Nullable SecurityProfile securityProfile,
        Map<String, byte[]> inputFiles,
        /** Inputs staged by host path and streamed into the container, never read into this process. */
        Map<String, java.nio.file.Path> inputFilesOnDisk,
        List<EvidenceDirectory> inputDirectories,
        String outputPath,
        Map<String, String> volumeMounts) {
    /** For runs whose inputs are all held in memory. */
    public SandboxSpec(
            UUID jobId,
            String image,
            @Nullable List<String> command,
            @Nullable Map<String, String> environment,
            @Nullable NetworkPolicy networkPolicy,
            ResourceLimits resourceLimits,
            @Nullable SecurityProfile securityProfile,
            @Nullable Map<String, byte[]> inputFiles,
            String outputPath,
            @Nullable Map<String, String> volumeMounts) {
        this(
                jobId,
                image,
                command,
                environment,
                networkPolicy,
                resourceLimits,
                securityProfile,
                inputFiles,
                Map.of(),
                outputPath,
                volumeMounts);
    }

    public SandboxSpec(
            UUID jobId,
            String image,
            @Nullable List<String> command,
            @Nullable Map<String, String> environment,
            @Nullable NetworkPolicy networkPolicy,
            ResourceLimits resourceLimits,
            @Nullable SecurityProfile securityProfile,
            @Nullable Map<String, byte[]> inputFiles,
            @Nullable Map<String, java.nio.file.Path> inputFilesOnDisk,
            String outputPath,
            @Nullable Map<String, String> volumeMounts) {
        this(
                jobId,
                image,
                command,
                environment,
                networkPolicy,
                resourceLimits,
                securityProfile,
                inputFiles,
                inputFilesOnDisk,
                List.of(),
                outputPath,
                volumeMounts);
    }

    public SandboxSpec(
            UUID jobId,
            String image,
            @Nullable List<String> command,
            @Nullable Map<String, String> environment,
            @Nullable NetworkPolicy networkPolicy,
            ResourceLimits resourceLimits,
            @Nullable SecurityProfile securityProfile,
            @Nullable Map<String, byte[]> inputFiles,
            @Nullable Map<String, java.nio.file.Path> inputFilesOnDisk,
            List<EvidenceDirectory> inputDirectories,
            String outputPath,
            @Nullable Map<String, String> volumeMounts) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.image = Objects.requireNonNull(image, "image must not be null");
        this.resourceLimits = Objects.requireNonNull(resourceLimits, "resourceLimits must not be null");
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath must not be null");
        if (this.image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        if (this.outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath must not be blank");
        }
        this.command = command != null ? List.copyOf(command) : List.of();
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
        this.networkPolicy = networkPolicy;
        this.securityProfile = securityProfile;
        this.inputFiles = inputFiles != null ? Map.copyOf(inputFiles) : Map.of();
        this.inputFilesOnDisk = inputFilesOnDisk != null ? Map.copyOf(inputFilesOnDisk) : Map.of();
        this.inputDirectories = List.copyOf(inputDirectories);
        this.volumeMounts =
                volumeMounts != null ? Collections.unmodifiableMap(new LinkedHashMap<>(volumeMounts)) : Map.of();
    }
}
