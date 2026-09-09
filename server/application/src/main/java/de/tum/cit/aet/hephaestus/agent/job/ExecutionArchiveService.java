package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.ExecutionArchiveDTO.AttemptDTO;
import de.tum.cit.aet.hephaestus.agent.job.ExecutionArchiveDTO.FileDTO;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Reuses Context Fabric storage and retention, without putting private transcripts in database listings. */
@Service
public class ExecutionArchiveService {
    static final String INPUT_MANIFEST = "execution-inputs.json";
    static final String OUTPUT_MANIFEST = "execution-outputs.json";
    private final FabricLayout layout;
    private final ContentAddressedStore cas;
    private final JsonMapper mapper;
    private final boolean enabled;

    public ExecutionArchiveService(
            FabricLayout layout,
            ContentAddressedStore cas,
            JsonMapper mapper,
            @Value("${hephaestus.practice-review.execution-capture.enabled:false}") boolean enabled) {
        this.layout = layout;
        this.cas = cas;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void captureInputs(AgentJob job, SandboxSpec spec) {
        if (!enabled) return;
        if (!spec.volumeMounts().isEmpty()) {
            throw new IllegalStateException("Execution capture requires materialized input files, not mutable mounts");
        }
        List<FileDTO> files = new ArrayList<>();
        spec.inputFiles().forEach((path, content) -> files.add(file("inputs/" + safePath(path), content)));
        spec.inputFilesOnDisk().forEach((path, source) -> {
            try {
                files.add(
                        new FileDTO("inputs/" + safePath(path), cas.put(source), Files.size(source), mediaType(path)));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not capture staged execution input", e);
            }
        });
        files.sort(Comparator.comparing(FileDTO::path));
        if (files.stream().map(FileDTO::path).distinct().count() != files.size()) {
            throw new IllegalStateException("Duplicate execution input path");
        }
        Path directory = attemptDirectory(job, job.getRetryCount());
        AttemptDTO attempt = new AttemptDTO(
                job.getRetryCount(),
                "INPUTS_CAPTURED",
                Instant.now(),
                spec.image(),
                MDC.get(StructuredLogKeys.TRACE_ID),
                List.copyOf(files));
        Path existing = directory.resolve(INPUT_MANIFEST);
        if (Files.exists(existing)) {
            AttemptDTO prior = read(existing);
            if (!prior.files().equals(attempt.files()) || !prior.image().equals(attempt.image())) {
                throw new IllegalStateException("Execution attempt inputs changed; original capture was preserved");
            }
            return;
        }
        write(directory, INPUT_MANIFEST, attempt);
    }

    public void captureOutputs(AgentJob job, SandboxResult result) {
        if (!enabled) return;
        Path directory = attemptDirectory(job, job.getRetryCount());
        AttemptDTO input = read(directory.resolve(INPUT_MANIFEST));
        List<FileDTO> files = new ArrayList<>(input.files());
        result.outputFiles().forEach((path, content) -> files.add(file("outputs/" + safePath(path), content)));
        files.add(file("execution/container.log", result.logs().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        files.add(file(
                "execution/result.json",
                mapper.writeValueAsBytes(Map.of(
                        "exitCode",
                        result.exitCode(),
                        "timedOut",
                        result.timedOut(),
                        "durationMs",
                        result.duration().toMillis()))));
        files.sort(Comparator.comparing(FileDTO::path));
        write(
                directory,
                OUTPUT_MANIFEST,
                new AttemptDTO(
                        input.attempt(),
                        "OUTPUTS_CAPTURED",
                        input.preparedAt(),
                        input.image(),
                        input.traceId(),
                        List.copyOf(files)));
    }

    /** Called only with the workspace and attempt resolved by authenticated proxy routing. */
    public void captureProxyRequest(
            long workspaceId,
            java.util.UUID jobId,
            int attempt,
            byte[] body,
            String piRequestSha256,
            io.micrometer.tracing.TraceContext traceContext) {
        if (!enabled) return;
        Path directory = layout.jobDir(jobId.toString())
                .resolve("execution")
                .resolve(Long.toString(workspaceId))
                .resolve(Integer.toString(attempt));
        // Capture can be enabled while an older execution is already running. Do not misrepresent
        // a proxy-only fragment as an archived attempt with complete staged inputs.
        if (!Files.exists(directory.resolve(INPUT_MANIFEST))) return;
        AttemptDTO input = read(directory.resolve(INPUT_MANIFEST));
        String requestId = java.util.UUID.randomUUID().toString();
        var files = List.of(
                file("proxy/" + requestId + "/request.json", body),
                file(
                        "proxy/" + requestId + "/context.json",
                        mapper.writeValueAsBytes(Map.of(
                                "piRequestSha256",
                                piRequestSha256,
                                "traceId",
                                traceContext.traceId(),
                                "spanId",
                                traceContext.spanId(),
                                "capturedAt",
                                Instant.now().toString()))));
        write(
                directory,
                "execution-proxy-" + requestId + ".json",
                new AttemptDTO(
                        attempt,
                        "PROXY_REQUEST_CAPTURED",
                        input.preparedAt(),
                        input.image(),
                        traceContext.traceId(),
                        files));
    }

    /** The caller must first load the job through its workspace-scoped repository query. */
    public ExecutionArchiveDTO describe(AgentJob job) {
        Path root = archiveDirectory(job);
        List<AttemptDTO> attempts = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (var directories = Files.list(root)) {
                for (Path directory :
                        directories.filter(Files::isDirectory).sorted().toList()) {
                    Path output = directory.resolve(OUTPUT_MANIFEST);
                    Path input = directory.resolve(INPUT_MANIFEST);
                    if (!Files.exists(input)) continue;
                    AttemptDTO base = read(Files.exists(output) ? output : input);
                    List<FileDTO> files = new ArrayList<>(base.files());
                    try (var proxyManifests = Files.list(directory)) {
                        proxyManifests
                                .filter(path -> path.getFileName().toString().startsWith("execution-proxy-")
                                        && path.getFileName().toString().endsWith(".json"))
                                .forEach(path -> files.addAll(read(path).files()));
                    }
                    files.sort(Comparator.comparing(FileDTO::path));
                    attempts.add(new AttemptDTO(
                            base.attempt(),
                            base.captureState(),
                            base.preparedAt(),
                            base.image(),
                            base.traceId(),
                            List.copyOf(files)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read execution capture", e);
            }
        }
        return new ExecutionArchiveDTO(1, job.getId(), job.getStatus().name(), List.copyOf(attempts));
    }

    /** Membership is checked before CAS access: a digest is not permission to read another job's content. */
    public byte[] content(AgentJob job, int attempt, String sha256) {
        if (!sha256.matches("[a-f0-9]{64}")) throw missing();
        FileDTO file = describe(job).attempts().stream()
                .filter(item -> item.attempt() == attempt)
                .flatMap(item -> item.files().stream())
                .filter(item -> item.sha256().equals(sha256))
                .findFirst()
                .orElseThrow(ExecutionArchiveService::missing);
        byte[] content = cas.get(file.sha256()).orElseThrow(ExecutionArchiveService::missing);
        if (content.length != file.bytes()
                || !ContentAddressedStore.sha256(content).equals(file.sha256())) {
            throw new IllegalStateException("Execution capture content failed integrity verification");
        }
        return content;
    }

    private static EntityNotFoundException missing() {
        return new EntityNotFoundException("Execution artifact", "not retained for this job and attempt");
    }

    private FileDTO file(String path, byte[] content) {
        return new FileDTO(path, cas.put(content), content.length, mediaType(path));
    }

    private Path archiveDirectory(AgentJob job) {
        return layout.jobDir(job.getId().toString())
                .resolve("execution")
                .resolve(Long.toString(job.getWorkspace().getId()));
    }

    private Path attemptDirectory(AgentJob job, int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("Negative attempt");
        return archiveDirectory(job).resolve(Integer.toString(attempt));
    }

    private AttemptDTO read(Path path) {
        try {
            return mapper.readValue(Files.readAllBytes(path), AttemptDTO.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Execution capture manifest is unavailable", e);
        }
    }

    private void write(Path directory, String name, AttemptDTO content) {
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "capture-", ".tmp");
            try {
                byte[] bytes = mapper.writeValueAsBytes(content);
                Files.write(temporary, bytes);
                Path target = directory.resolve(name);
                if (Files.exists(target)) {
                    if (!java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
                        throw new IllegalStateException("Execution capture is immutable");
                    }
                    return;
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist execution capture", e);
        }
    }

    private static String safePath(String value) {
        Path path = Path.of(value);
        if (value.isBlank()
                || path.isAbsolute()
                || value.contains("\\")
                || path.normalize().startsWith("..")
                || !path.normalize().toString().equals(value)) {
            throw new IllegalArgumentException("Unsafe execution artifact path");
        }
        return value;
    }

    private static String mediaType(String path) {
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".jsonl")) return "application/x-ndjson";
        return "application/octet-stream";
    }
}
