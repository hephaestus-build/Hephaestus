package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SecretDiffScannerTest extends BaseUnitTest {
    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final NativeGitExecutor git = mock(NativeGitExecutor.class);
    private final JsonMapper mapper = new JsonMapper();
    private final SecretDiffScanner scanner = new SecretDiffScanner(files, git, mapper);
    private AgentJob job;
    private ObjectNode snapshot;

    @TempDir
    private Path repository;

    @BeforeEach
    void setUp() {
        job = new AgentJob();
        snapshot = mapper.createObjectNode();
        var sources = snapshot.putObject("manifest").putArray("sources");
        var diff = sources.addObject().put("kind", "scm.pull-request.diff");
        diff.putObject("state")
                .put("availability", "AVAILABLE")
                .putObject("facts")
                .put("immutableIdentity", "a".repeat(40) + ":" + "b".repeat(40));
        var tree = sources.addObject().put("kind", "scm.repository.tree");
        tree.putObject("state").put("availability", "AVAILABLE");
        tree.putArray("artifacts")
                .addObject()
                .put("path", SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD")
                .put("sha256", "c".repeat(64));
        tree.withArray("artifacts")
                .addObject()
                .put("path", SandboxLayout.REPO_MOUNT_RELATIVE + ".git/hephaestus-captured-refs")
                .put("sha256", "e".repeat(64));
        job.setEvidenceSnapshot(snapshot);
        when(files.repositoryForVerification(job, "c".repeat(64), "e".repeat(64)))
                .thenReturn(repository);
    }

    @Test
    void shouldUseTheCapturedRangeAndReturnOnlyRedactedVerdicts() {
        doAnswer(invocation -> {
                    NativeGitExecutor.Request request = invocation.getArgument(1);
                    assertThat(request.operation()).isEqualTo(NativeGitExecutor.Operation.SCAN_SECRETS);
                    assertThat(request.revisions()).containsExactly("a".repeat(40), "b".repeat(40));
                    OutputStream output = invocation.getArgument(3);
                    output.write(
                            ("{\"skipped\":[],\"verdicts\":[{\"path\":\"source.java\",\"line\":3,\"ruleId\":\"aws-access-token\",\"lineHash\":\""
                                            + "d".repeat(64) + "\"}]}")
                                    .getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        assertThat(scanner.scan(job))
                .containsExactly(new SecretDiffScanner.SecretHit("source.java", 3, "d".repeat(64), "aws-access-token"));
    }

    @Test
    void shouldRejectScannerFailureRatherThanReportNoSecrets() {
        doThrow(new IllegalStateException("helper failed"))
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        assertThatThrownBy(() -> scanner.scan(job)).isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldRejectUnexpectedFieldsRatherThanAcceptRawSecretReports() {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    output.write("{\"skipped\":[],\"verdicts\":[{\"Secret\":\"never accepted\"}]}"
                            .getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        assertThatThrownBy(() -> scanner.scan(job)).isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldRejectVerdictsThatAreNotAnArray() {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    output.write("{\"skipped\":[],\"verdicts\":{}}".getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        assertThatThrownBy(() -> scanner.scan(job))
                .isInstanceOf(JobDeliveryException.class)
                .cause()
                .hasMessage("Invalid secret scan verdicts");
    }

    @Test
    void shouldRefuseToScanWhenTheCapturedDiffRangeIsMissing() {
        snapshot.withObject("/manifest/sources/0/state/facts").remove("immutableIdentity");
        assertThatThrownBy(() -> scanner.scan(job))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Secret scan requires the captured diff range and repository identity");
        verifyNoInteractions(files, git);
    }

    @Test
    void shouldRefuseToScanWhenTheCapturedDiffRangeIsNotAPairOfObjectIds() {
        snapshot.withObject("/manifest/sources/0/state/facts").put("immutableIdentity", "main..feature");
        assertThatThrownBy(() -> scanner.scan(job))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Secret scan requires the captured diff range and repository identity");
        verifyNoInteractions(files, git);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/test/fixtures/keys.txt",
                "examples/demo.env",
                "packages/sdk/examples/quickstart.ts",
                "e2e/samples/seed.json",
                "example/demo.env"
            })
    void shouldTreatTestAndSampleDirectoriesAsLowSignalAtAnyDepth(String path) {
        assertThat(scanner.isLowSignalPath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/main/Weather.swift",
                "src/main/java/com/example/util/CacheManager.java",
                "app/src/main/kotlin/com/sample/Api.kt"
            })
    void shouldNotTreatAPackageNamedExampleAsLowSignal(String path) {
        assertThat(scanner.isLowSignalPath(path)).isFalse();
    }
}
