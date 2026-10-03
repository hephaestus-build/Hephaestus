package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RepositoryTreeContentSourceTest extends BaseUnitTest {

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    private RepositoryTreeContentSource source;
    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void releaseCapturedTrees() throws Exception {
        for (var cleanup : cleanups) cleanup.close();
    }

    @BeforeEach
    void setUp() {
        source = new RepositoryTreeContentSource(
                gitRepositoryManager,
                Mockito.mock(ReviewRepositoryPreparer.class),
                Mockito.mock(PersonDataCopyRecorder.class));
    }

    @TempDir
    Path stagingDir;

    @Test
    void shouldMaterializePinnedRepositoryWithGitMetadata() {
        AgentJob job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(true);
        when(gitRepositoryManager.readTreeSnapshot(
                        new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789"))
                .thenReturn(new GitRepositoryManager.GitTreeSnapshot(
                        stagingDir,
                        "0123456789012345678901234567890123456789",
                        "1123456789012345678901234567890123456789",
                        12,
                        1,
                        true,
                        Set.of()));

        var contribution = source.capture(new ContextRequest.PracticeReviewRequest(job), source.sourceKinds());
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.filesOnDisk())
                .containsOnlyKeys("repos/reviewed/.git/HEAD", "repos/reviewed/.git/hephaestus-captured-refs");
        assertThat(contribution.directories()).containsExactly(new EvidenceDirectory("repos/reviewed/", stagingDir));
        verify(gitRepositoryManager)
                .readTreeSnapshot(new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789");
    }

    @Test
    @DisplayName("reports excluded tree entries as PARTIAL and names the exclusions")
    void shouldReportPartialWhenTreeEntriesAreExcluded() {
        AgentJob job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(true);
        when(gitRepositoryManager.readTreeSnapshot(
                        new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789"))
                .thenReturn(new GitRepositoryManager.GitTreeSnapshot(
                        stagingDir,
                        "0123456789012345678901234567890123456789",
                        "1123456789012345678901234567890123456789",
                        12,
                        40_000,
                        false,
                        Set.of(
                                GitRepositoryManager.TREE_LIMITATION_UNSAFE_PATH,
                                GitRepositoryManager.TREE_LIMITATION_SUBMODULE)));

        var contribution = source.capture(new ContextRequest.PracticeReviewRequest(job), source.sourceKinds());
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        // COMPLETE here would license a practice to say "this does not exist anywhere in the
        // repository" about a tree with excluded entries.
        assertThat(contribution.completeness())
                .containsEntry(new SourceKind("scm.repository.tree"), SourceCompleteness.PARTIAL);
        assertThat(contribution.captureLimitations().get(new SourceKind("scm.repository.tree")))
                .containsExactlyInAnyOrder(
                        GitRepositoryManager.TREE_LIMITATION_UNSAFE_PATH,
                        GitRepositoryManager.TREE_LIMITATION_SUBMODULE);
    }

    @Test
    void shouldRejectUnpinnedTree() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(true);
        assertThatThrownBy(() ->
                        source.capture(new ContextRequest.PracticeReviewRequest(job(17L, null)), source.sourceKinds()))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining("commit_sha");
    }

    @Test
    void shouldReportOperationalGitFailureAsCollectionError() {
        AgentJob job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(true);
        when(gitRepositoryManager.readTreeSnapshot(
                        new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789"))
                .thenThrow(new GitRepositoryManager.GitOperationException("unreadable commit", new IOException()));

        assertThatThrownBy(() -> source.capture(new ContextRequest.PracticeReviewRequest(job), source.sourceKinds()))
                .isInstanceOf(EvidenceCollectionException.class)
                .hasMessageContaining("Could not capture repository tree");
    }

    @Test
    void shouldReportDisabledCheckoutAsNotCollectedRatherThanFailing() {
        AgentJob job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(false);

        var contribution = source.capture(new ContextRequest.PracticeReviewRequest(job), source.sourceKinds());
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.stateOverrides())
                .containsExactly(entry(
                        new SourceKind("scm.repository.tree"),
                        new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED)));
    }

    @Test
    void shouldReportAnUnclonedRepositoryAsUnavailableRatherThanFailing() {
        AgentJob job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(false);

        var contribution = source.capture(new ContextRequest.PracticeReviewRequest(job), source.sourceKinds());
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.stateOverrides())
                .containsExactly(entry(
                        new SourceKind("scm.repository.tree"),
                        new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_WORKING_COPY)));
    }

    private static AgentJob job(long repositoryId, @Nullable String commitSha) {
        JsonMapper mapper = JsonMapper.builder().build();
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("repository_id", repositoryId);
        if (commitSha != null) {
            metadata.put("commit_sha", commitSha);
        }
        AgentJob job = new AgentJob();
        var workspace = new Workspace();
        workspace.setId(99L);
        job.setWorkspace(workspace);
        job.setMetadata(metadata);
        return job;
    }
}
