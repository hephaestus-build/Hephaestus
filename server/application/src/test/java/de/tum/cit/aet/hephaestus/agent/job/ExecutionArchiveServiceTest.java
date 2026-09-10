package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.integration.core.fabric.ContentAddressedStore;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ExecutionArchiveServiceTest extends BaseUnitTest {
    @TempDir
    Path root;

    private ContentAddressedStore cas;
    private FabricLayout layout;
    private ExecutionArchiveService service;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        layout = new FabricLayout(root.toString());
        cas = new ContentAddressedStore(layout);
        service = new ExecutionArchiveService(layout, cas, mapper, true);
    }

    private AgentJob job(long workspaceId) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setStatus(AgentJobStatus.RUNNING);
        return job;
    }

    private SandboxSpec spec(AgentJob job, Map<String, byte[]> inputs) {
        return new SandboxSpec(
                job.getId(),
                "runner@sha256:fixture",
                List.of("node", ".run-pi.ts"),
                Map.of("LLM_PROXY_TOKEN", "private-credential-never-export"),
                null,
                ResourceLimits.DEFAULT,
                null,
                inputs,
                "/workspace/out",
                Map.of());
    }

    @Test
    void shouldRetainInputsOutputsAndSeparateRetriesWithoutCredentials() throws Exception {
        AgentJob job = job(1);
        byte[] input = "exact prompt and evidence".getBytes(StandardCharsets.UTF_8);
        service.captureInputs(job, spec(job, Map.of("task.json", input)));
        service.captureInputs(job, spec(job, Map.of("task.json", input)));
        var inputManifest = manifest(job, 0, "execution-inputs.json");
        assertThat(inputManifest.captureState()).isEqualTo("INPUTS_CAPTURED");
        assertThat(cas.get(inputManifest.files().getFirst().sha256())).contains(input);
        byte[] transcript = "{\"type\":\"session\"}\n".getBytes(StandardCharsets.UTF_8);
        service.captureOutputs(
                job,
                new SandboxResult(
                        137,
                        Map.of("trace/sessions/session.jsonl", transcript),
                        "timed out",
                        true,
                        Duration.ofSeconds(1)));
        job.setRetryCount(1);
        service.captureInputs(job, spec(job, Map.of("task.json", "new attempt".getBytes(StandardCharsets.UTF_8))));
        var first = manifest(job, 0, "execution-outputs.json");
        var second = manifest(job, 1, "execution-inputs.json");
        assertThat(first.captureState()).isEqualTo("OUTPUTS_CAPTURED");
        assertThat(second.files()).hasSize(1);
        assertThat(mapper.writeValueAsString(first)).doesNotContain("private-credential-never-export");
        var session = first.files().stream()
                .filter(file -> file.path().endsWith("session.jsonl"))
                .findFirst()
                .orElseThrow();
        assertThat(cas.get(session.sha256())).contains(transcript);
        assertThat(second.files()).noneMatch(file -> file.sha256().equals(session.sha256()));
        AgentJob otherWorkspace = job(2);
        otherWorkspace.setId(job.getId());
        service.captureInputs(otherWorkspace, spec(otherWorkspace, Map.of("task.json", new byte[] {7})));
        assertThat(manifest(otherWorkspace, 0, "execution-inputs.json").files()).isNotEqualTo(inputManifest.files());
        assertThat(manifest(job, 0, "execution-inputs.json")).isEqualTo(inputManifest);
    }

    private Path directory(AgentJob job, int attempt) {
        return layout.jobDir(job.getId().toString())
                .resolve("execution")
                .resolve(job.getWorkspace().getId().toString())
                .resolve(Integer.toString(attempt));
    }

    private ExecutionCaptureManifest manifest(AgentJob job, int attempt, String name) throws Exception {
        return mapper.readValue(
                Files.readAllBytes(directory(job, attempt).resolve(name)), ExecutionCaptureManifest.class);
    }

    @Test
    void shouldPreserveOriginalCaptureWhenInputsChangeOrPathsEscape() {
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        assertThatThrownBy(() -> service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {2}))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed");
        assertThatThrownBy(() -> service.captureInputs(job, spec(job, Map.of("../escape", new byte[] {1}))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.captureOutputs(
                        job, new SandboxResult(0, Map.of("/escape", new byte[] {1}), "", false, Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRetainActualProxyRequestsAndLinkThemToNativeRequests() throws Exception {
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        byte[] request = "{\"model\":\"actual-upstream-model\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var traceContext = org.mockito.Mockito.mock(io.micrometer.tracing.TraceContext.class);
        org.mockito.Mockito.when(traceContext.traceId()).thenReturn("a".repeat(32));
        org.mockito.Mockito.when(traceContext.spanId()).thenReturn("c".repeat(16));
        service.captureProxyRequest(job.getWorkspace().getId(), job.getId(), 0, request, "b".repeat(64), traceContext);
        service.captureOutputs(job, new SandboxResult(0, Map.of(), "", false, Duration.ZERO));
        Path proxyManifest;
        try (var files = Files.list(directory(job, 0))) {
            proxyManifest = files.filter(path -> path.getFileName().toString().startsWith("execution-proxy-"))
                    .findFirst()
                    .orElseThrow();
        }
        var captured = mapper.readValue(Files.readAllBytes(proxyManifest), ExecutionCaptureManifest.class);
        assertThat(captured.files()).hasSize(2);
        var file = captured.files().stream()
                .filter(item -> item.path().endsWith("/request.json"))
                .findFirst()
                .orElseThrow();
        assertThat(cas.get(file.sha256())).contains(request);
        var context = captured.files().stream()
                .filter(item -> item.path().endsWith("/context.json"))
                .findFirst()
                .orElseThrow();
        assertThat(new String(cas.get(context.sha256()).orElseThrow(), StandardCharsets.UTF_8))
                .contains("b".repeat(64), "a".repeat(32), "c".repeat(16));
    }

    @Test
    void shouldNotCaptureAnythingUnlessExplicitlyEnabled() {
        service = new ExecutionArchiveService(layout, cas, mapper, false);
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        service.captureOutputs(job, new SandboxResult(0, Map.of(), "private", false, Duration.ZERO));
        assertThat(layout.jobsRoot()).doesNotExist();
    }
}
