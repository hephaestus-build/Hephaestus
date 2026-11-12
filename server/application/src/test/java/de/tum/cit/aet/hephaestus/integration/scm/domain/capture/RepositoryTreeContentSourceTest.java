package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.evidence.capture.EvidenceDirectory;
import de.tum.cit.aet.hephaestus.integration.core.fabric.EvidenceSpoolFixtures;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class RepositoryTreeContentSourceTest extends BaseUnitTest {

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    private RepositoryTreeContentSource source;
    private final ReviewRepositoryPreparer preparer = Mockito.mock(ReviewRepositoryPreparer.class);
    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void releaseCapturedTrees() throws Exception {
        for (var cleanup : cleanups) cleanup.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        stagingDir = EvidenceSpoolFixtures.directory(new FabricLayout(root.toString()).spool(), 99)
                .path();
        Git.init().setDirectory(stagingDir.toFile()).call().close();
        source = new RepositoryTreeContentSource(
                gitRepositoryManager, preparer, Mockito.mock(PersonDataCopyRecorder.class));
    }

    @TempDir
    Path root;

    Path stagingDir;

    @Test
    void shouldMaterializePinnedRepositoryWithGitMetadata() {
        ScmCaptureContext job = job(17L, "0123456789012345678901234567890123456789");
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

        var contribution = source.capture(job, Set.of(RepositoryTreeContentSource.KIND));
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.filesOnDisk())
                .containsOnlyKeys("repos/reviewed/.git/HEAD", "repos/reviewed/.git/hephaestus-captured-refs");
        assertThat(contribution.directories())
                .containsExactly(new EvidenceDirectory(
                        "repos/reviewed/",
                        stagingDir,
                        new CheckoutKey(
                                17L,
                                "0123456789012345678901234567890123456789",
                                "1123456789012345678901234567890123456789",
                                1,
                                null)));
        verify(gitRepositoryManager)
                .readTreeSnapshot(new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789");
    }

    @Test
    @DisplayName("reports excluded tree entries as PARTIAL and names the exclusions")
    void shouldReportPartialWhenTreeEntriesAreExcluded() {
        ScmCaptureContext job = job(17L, "0123456789012345678901234567890123456789");
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

        var contribution = source.capture(job, Set.of(RepositoryTreeContentSource.KIND));
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
        assertThatThrownBy(() -> source.capture(job(17L, null), Set.of(RepositoryTreeContentSource.KIND)))
                .isInstanceOf(ItemCaptureException.class)
                .hasMessageContaining("commit_sha");
    }

    @Test
    void shouldReportOperationalGitFailureAsCollectionError() {
        ScmCaptureContext job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(true);
        when(gitRepositoryManager.readTreeSnapshot(
                        new RepositoryKey(99L, 17L), "0123456789012345678901234567890123456789"))
                .thenThrow(new GitRepositoryManager.GitOperationException("unreadable commit", new IOException()));

        assertThatThrownBy(() -> source.capture(job, Set.of(RepositoryTreeContentSource.KIND)))
                .isInstanceOf(ItemCaptureException.class)
                .hasMessageContaining("Could not capture repository tree");
    }

    @Test
    void shouldReportDisabledCheckoutAsNotCollectedRatherThanFailing() {
        ScmCaptureContext job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(false);

        var contribution = source.capture(job, Set.of(RepositoryTreeContentSource.KIND));
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.stateOverrides())
                .containsExactly(entry(
                        new SourceKind("scm.repository.tree"),
                        new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED)));
    }

    @Test
    void shouldReportAnUnclonedRepositoryAsUnavailableRatherThanFailing() {
        ScmCaptureContext job = job(17L, "0123456789012345678901234567890123456789");
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(new RepositoryKey(99L, 17L)))
                .thenReturn(false);

        var contribution = source.capture(job, Set.of(RepositoryTreeContentSource.KIND));
        if (contribution.cleanup() != null) cleanups.add(contribution.cleanup());

        assertThat(contribution.files()).isEmpty();
        assertThat(contribution.stateOverrides())
                .containsExactly(entry(
                        new SourceKind("scm.repository.tree"),
                        new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_WORKING_COPY)));
    }

    @Test
    void shouldRecordATreeOnlyFetchFailureAsUnavailable() {
        var request = job(17L, "a".repeat(40));
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(preparer.prepare(request))
                .thenThrow(
                        new GitRepositoryManager.RepositoryFetchException(new TransportException("private upstream")));

        var captured = source.capture(request, Set.of(RepositoryTreeContentSource.KIND));

        assertThat(captured.stateOverrides())
                .containsEntry(
                        RepositoryTreeContentSource.KIND,
                        new SourceCaptureState.Unavailable(SourceAbsenceReason.REPOSITORY_FETCH_FAILED));
        assertThat(captured.directories()).isEmpty();
        assertThat(captured.citableTexts()).isEmpty();
    }

    private static ScmCaptureContext job(long repositoryId, @Nullable String commitSha) {
        return ScmCaptureFixtures.context(
                new ObjectMapper()
                        .createObjectNode()
                        .put("repository_id", repositoryId)
                        .put("commit_sha", commitSha),
                99L,
                false);
    }
}
