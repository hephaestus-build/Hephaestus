package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.agent.context.providers.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CapturedEvidenceTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private JobEvidenceFiles evidenceFiles;

    private static AgentJob jobWith(ObjectNode snapshot) {
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setEvidenceSnapshot(snapshot);
        return job;
    }

    @Test
    void shouldReadBackTheManifestAnAttemptRecorded() {
        Instant capturedAt = Instant.parse("2026-08-03T00:00:00Z");
        String head = "b".repeat(40);
        var manifest = new ArtifactSourceManifest(
                ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                "0".repeat(64),
                ArtifactKinds.PULL_REQUEST.value(),
                capturedAt,
                List.of(
                        new SourceCapture(
                                PracticeSubjectClause.DIFF_SOURCE,
                                new SourceCaptureState.Available(
                                        SourceContentState.NON_EMPTY,
                                        SourceCompleteness.PARTIAL,
                                        new SourceCaptureFacts(
                                                capturedAt, null, capturedAt, "a".repeat(40) + ":" + head),
                                        List.of("TRUNCATED")),
                                List.of(new SourceArtifact(
                                        CapturedEvidence.DIFF_ARTIFACT, "text/x-diff", "c".repeat(64), 7))),
                        new SourceCapture(
                                RepositoryTreeContentSource.KIND,
                                new SourceCaptureState.Available(
                                        SourceContentState.NON_EMPTY,
                                        SourceCompleteness.COMPLETE,
                                        new SourceCaptureFacts(capturedAt, null, null, head + ":" + "d".repeat(40))),
                                List.of(new SourceArtifact(
                                        SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD",
                                        "text/plain",
                                        "e".repeat(64),
                                        41))),
                        new SourceCapture(
                                new de.tum.cit.aet.hephaestus.evidence.SourceKind("scm.pull-request.comments"),
                                new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND),
                                List.of())));
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("manifest", mapper.valueToTree(manifest));
        snapshot.set(
                SecretScan.SNAPSHOT_NODE,
                mapper.valueToTree(new SecretScan(
                        CapturedEvidence.DIFF_ARTIFACT,
                        "c".repeat(64),
                        List.of(new SecretScan.Hit("src/Config.java", 3, "f".repeat(64), "aws-access-token")))));

        CapturedEvidence captured = CapturedEvidence.of(jobWith(snapshot), mapper);

        assertThat(captured.contractVersion()).isEqualTo(ArtifactSourceCatalogRegistry.CURRENT_VERSION);
        assertThat(captured.availableSources())
                .containsExactlyInAnyOrder(PracticeSubjectClause.DIFF_SOURCE, RepositoryTreeContentSource.KIND);
        assertThat(captured.artifact(CapturedEvidence.DIFF_ARTIFACT))
                .isEqualTo(new CapturedEvidence.Artifact(PracticeSubjectClause.DIFF_SOURCE, "c".repeat(64)));
        assertThat(captured.immutableIdentity(PracticeSubjectClause.DIFF_SOURCE))
                .isEqualTo("a".repeat(40) + ":" + head);
        assertThat(captured.pinnedHead()).isEqualTo(head);
        assertThat(captured.requireArtifact(
                                RepositoryTreeContentSource.KIND, SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD")
                        .sha256())
                .isEqualTo("e".repeat(64));
        var scan = captured.secretScan();
        assertThat(scan).isNotNull();
        assertThat(scan.hits())
                .singleElement()
                .extracting(SecretScan.Hit::ruleId)
                .isEqualTo("aws-access-token");
    }

    @Test
    void shouldRefuseASnapshotWhoseManifestThisRuntimeCannotRead() {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.putObject("manifest").put("contractVersion", "1.1.0").putArray("sources");

        assertThatThrownBy(() -> CapturedEvidence.of(jobWith(snapshot), mapper))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("not a readable manifest");
    }

    @Test
    void shouldRefuseAnArtifactClaimedByTwoSources() {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.core", null),
                "inputs/context/shared.json",
                "a".repeat(64));
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.comments", null),
                "inputs/context/shared.json",
                "b".repeat(64));

        assertThatThrownBy(() -> CapturedEvidence.of(jobWith(snapshot), mapper))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("multiple sources");
    }

    @Test
    void shouldRefuseAMisattributedArtifactWhileNamingTheSource() {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, "scm.pull-request.core", null),
                SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD",
                "a".repeat(64));
        CapturedEvidence captured = CapturedEvidence.of(jobWith(snapshot), mapper);

        assertThatThrownBy(() -> captured.requireArtifact(
                        RepositoryTreeContentSource.KIND, SandboxLayout.REPO_MOUNT_RELATIVE + ".git/HEAD"))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("scm.repository.tree");
        assertThatThrownBy(captured::pinnedHead)
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("no pinned commit identity");
    }

    @Test
    void shouldReadTheChangedPathsFromTheCapturedListingAndNothingWhenNoDiffWasCaptured() {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        ObjectNode diff = EvidenceSnapshotFixtures.availableSource(
                snapshot, PracticeSubjectClause.DIFF_SOURCE.value(), "a".repeat(40) + ":" + "b".repeat(40));
        EvidenceSnapshotFixtures.artifact(diff, CapturedEvidence.DIFF_PATHS_ARTIFACT, "c".repeat(64));
        AgentJob job = jobWith(snapshot);
        when(evidenceFiles.inspect(eq(job), eq(CapturedEvidence.DIFF_PATHS_ARTIFACT), eq("c".repeat(64)), any()))
                .thenAnswer(invocation -> {
                    JobEvidenceFiles.TextInspection<?> inspection = invocation.getArgument(3);
                    return Optional.of(inspection.inspect(new StringReader("src/A.java\0src/B.java\0")));
                });

        assertThat(CapturedEvidence.of(job, mapper).diffPaths(job, evidenceFiles))
                .containsExactlyInAnyOrder("src/A.java", "src/B.java");

        EvidenceSnapshotFixtures.unavailable(diff);
        assertThat(CapturedEvidence.of(job, mapper).diffPaths(job, evidenceFiles))
                .isEmpty();
    }

    @Test
    void shouldRefuseACapturedDiffWithoutItsPathListing() {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(mapper);
        EvidenceSnapshotFixtures.artifact(
                EvidenceSnapshotFixtures.availableSource(snapshot, PracticeSubjectClause.DIFF_SOURCE.value(), null),
                CapturedEvidence.DIFF_ARTIFACT,
                "c".repeat(64));
        AgentJob job = jobWith(snapshot);

        assertThatThrownBy(() -> CapturedEvidence.of(job, mapper).diffPaths(job, evidenceFiles))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("no diff artifact");
        org.mockito.Mockito.verify(evidenceFiles, org.mockito.Mockito.never())
                .inspect(any(), anyString(), anyString(), any());
    }
}
