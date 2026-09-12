package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class SecretDiffScannerTest extends BaseUnitTest {

    private static final String RANGE = "a".repeat(40) + ":" + "b".repeat(40);
    private static final String DIFF_SHA = "c".repeat(64);
    private static final UUID JOB_ID = UUID.randomUUID();

    @Mock
    private NativeGitExecutor git;

    private final JsonMapper mapper = new JsonMapper();

    @TempDir
    private Path repository;

    private SecretDiffScanner scanner() {
        return new SecretDiffScanner(git, mapper);
    }

    private PreparedEvidence evidence(@Nullable String range, boolean withRepository) {
        var facts = new SourceCaptureFacts(Instant.parse("2026-08-03T00:00:00Z"), null, null, range);
        var diff = new SourceCapture(
                PracticeSubjectClause.DIFF_SOURCE,
                new SourceCaptureState.Available(SourceContentState.NON_EMPTY, SourceCompleteness.COMPLETE, facts),
                List.of(new SourceArtifact(CapturedEvidence.DIFF_ARTIFACT, "text/x-diff", DIFF_SHA, 12)));
        var tree = new SourceCapture(
                RepositoryTreeContentSource.KIND,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(
                                Instant.parse("2026-08-03T00:00:00Z"),
                                null,
                                null,
                                "b".repeat(40) + ":" + "d".repeat(40))),
                List.of(new SourceArtifact(
                        SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD", "text/plain", "e".repeat(64), 41)));
        var manifest = new ArtifactSourceManifest(
                ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                "0".repeat(64),
                ArtifactKinds.PULL_REQUEST.value(),
                Instant.parse("2026-08-03T00:00:00Z"),
                List.of(diff, tree));
        return new PreparedEvidence(
                Map.of(),
                Map.of(),
                List.of(),
                manifest,
                withRepository
                        ? List.of(new EvidenceDirectory(SandboxLayout.REPO_MOUNT_RELATIVE, repository))
                        : List.of());
    }

    private void answer(String report) {
        doAnswer(invocation -> {
                    OutputStream output = invocation.getArgument(3);
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
    }

    @Test
    void shouldScanTheCapturedRangeInTheSnapshotAndBindTheVerdictsToTheDiff() {
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

        SecretScan scan = scanner().scan(JOB_ID, evidence(RANGE, true));

        assertThat(scan.artifactPath()).isEqualTo(CapturedEvidence.DIFF_ARTIFACT);
        assertThat(scan.artifactSha256()).isEqualTo(DIFF_SHA);
        assertThat(scan.hits())
                .containsExactly(new SecretScan.Hit("source.java", 3, "d".repeat(64), "aws-access-token"));
    }

    @Test
    void shouldRejectScannerFailureRatherThanReportNoSecrets() {
        doThrow(new IllegalStateException("helper failed"))
                .when(git)
                .executeInSnapshot(eq(repository), any(), any(), any());
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence(RANGE, true)))
                .isInstanceOf(JobPreparationException.class);
    }

    @Test
    void shouldRejectUnexpectedFieldsRatherThanAcceptRawSecretReports() {
        answer("{\"skipped\":[],\"verdicts\":[{\"Secret\":\"never accepted\"}]}");
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence(RANGE, true)))
                .isInstanceOf(JobPreparationException.class);
    }

    @Test
    void shouldRejectVerdictsThatAreNotAnArray() {
        answer("{\"skipped\":[],\"verdicts\":{}}");
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence(RANGE, true)))
                .isInstanceOf(JobPreparationException.class)
                .cause()
                .hasMessage("Invalid secret scan verdicts");
    }

    @Test
    void shouldRefuseToScanWhenTheCapturedDiffRangeIsMissing() {
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence(null, true)))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageStartingWith("Secret scan requires the captured diff range and repository snapshot");
        verifyNoInteractions(git);
    }

    @Test
    void shouldRefuseToScanWhenTheCapturedDiffRangeIsNotAPairOfObjectIds() {
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence("main..feature", true)))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageStartingWith("Secret scan requires the captured diff range and repository snapshot");
        verifyNoInteractions(git);
    }

    @Test
    void shouldRefuseToScanWhenNoRepositorySnapshotWasStaged() {
        assertThatThrownBy(() -> scanner().scan(JOB_ID, evidence(RANGE, false)))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageStartingWith("Secret scan requires the captured diff range and repository snapshot");
        verifyNoInteractions(git);
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
        assertThat(scanner().isLowSignalPath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/main/Weather.swift",
                "src/main/java/com/example/util/CacheManager.java",
                "app/src/main/kotlin/com/sample/Api.kt"
            })
    void shouldNotTreatAPackageNamedExampleAsLowSignal(String path) {
        assertThat(scanner().isLowSignalPath(path)).isFalse();
    }
}
