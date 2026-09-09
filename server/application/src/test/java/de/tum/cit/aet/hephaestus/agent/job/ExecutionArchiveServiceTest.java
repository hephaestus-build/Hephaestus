package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
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
    void shouldRetainInputsOutputsAndSeparateRetriesWithoutCredentials() {
        AgentJob job = job(1);
        byte[] input = "exact prompt and evidence".getBytes(StandardCharsets.UTF_8);
        service.captureInputs(job, spec(job, Map.of("task.json", input)));
        service.captureInputs(job, spec(job, Map.of("task.json", input)));
        assertThat(service.describe(job).attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.captureState()).isEqualTo("INPUTS_CAPTURED");
            assertThat(service.content(job, 0, attempt.files().getFirst().sha256()))
                    .isEqualTo(input);
        });
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
        var archive = service.describe(job);
        assertThat(archive.attempts()).hasSize(2);
        assertThat(archive.attempts().getFirst().captureState()).isEqualTo("OUTPUTS_CAPTURED");
        assertThat(mapper.writeValueAsString(archive)).doesNotContain("private-credential-never-export");
        var session = archive.attempts().getFirst().files().stream()
                .filter(file -> file.path().endsWith("session.jsonl"))
                .findFirst()
                .orElseThrow();
        assertThat(service.content(job, 0, session.sha256())).isEqualTo(transcript);
        assertThatThrownBy(() -> service.content(job, 1, session.sha256())).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shouldRejectCrossWorkspaceCrossJobAndUnreferencedDigests() {
        AgentJob first = job(1);
        service.captureInputs(first, spec(first, Map.of("task.json", new byte[] {1})));
        String sha =
                service.describe(first).attempts().getFirst().files().getFirst().sha256();
        AgentJob otherWorkspace = job(2);
        otherWorkspace.setId(first.getId());
        assertThat(service.describe(otherWorkspace).attempts()).isEmpty();
        assertThatThrownBy(() -> service.content(otherWorkspace, 0, sha)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.content(job(1), 0, sha)).isInstanceOf(EntityNotFoundException.class);
        String unreferenced = cas.put(new byte[] {7});
        assertThatThrownBy(() -> service.content(first, 0, unreferenced)).isInstanceOf(EntityNotFoundException.class);
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
    void shouldFailIntegrityVerificationAfterStoredContentIsCorrupted() throws Exception {
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        String sha =
                service.describe(job).attempts().getFirst().files().getFirst().sha256();
        Files.write(cas.pathFor(sha), new byte[] {2});
        assertThatThrownBy(() -> service.content(job, 0, sha))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void shouldRetainActualProxyRequestsAndLinkThemToNativeRequests() {
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        byte[] request = "{\"model\":\"actual-upstream-model\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var traceContext = org.mockito.Mockito.mock(io.micrometer.tracing.TraceContext.class);
        org.mockito.Mockito.when(traceContext.traceId()).thenReturn("a".repeat(32));
        org.mockito.Mockito.when(traceContext.spanId()).thenReturn("c".repeat(16));
        service.captureProxyRequest(job.getWorkspace().getId(), job.getId(), 0, request, "b".repeat(64), traceContext);
        service.captureOutputs(job, new SandboxResult(0, Map.of(), "", false, Duration.ZERO));
        var captured = service.describe(job).attempts().getFirst();
        var file = captured.files().stream()
                .filter(item -> item.path().endsWith("/request.json"))
                .findFirst()
                .orElseThrow();
        assertThat(service.content(job, 0, file.sha256())).isEqualTo(request);
        var context = captured.files().stream()
                .filter(item -> item.path().endsWith("/context.json"))
                .findFirst()
                .orElseThrow();
        assertThat(new String(service.content(job, 0, context.sha256()), java.nio.charset.StandardCharsets.UTF_8))
                .contains("b".repeat(64), "a".repeat(32), "c".repeat(16));
    }

    @Test
    void shouldNotCaptureAnythingUnlessExplicitlyEnabled() {
        service = new ExecutionArchiveService(layout, cas, mapper, false);
        AgentJob job = job(1);
        service.captureInputs(job, spec(job, Map.of("task.json", new byte[] {1})));
        service.captureOutputs(job, new SandboxResult(0, Map.of(), "private", false, Duration.ZERO));
        assertThat(service.describe(job).attempts()).isEmpty();
        assertThat(layout.jobsRoot()).doesNotExist();
    }
}
