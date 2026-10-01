package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceRefusal;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class WorkspaceFolderRendererTest extends BaseUnitTest {
    @TempDir
    Path root;

    private final JsonMapper mapper = new JsonMapper();
    private final ArtifactSourceCatalogRegistry policies = mock(ArtifactSourceCatalogRegistry.class);
    private final DocumentProjection documents = mock(DocumentProjection.class);
    private final ReviewRepositoryPreparer repositories = mock(ReviewRepositoryPreparer.class);
    private final GitRepositoryManager git = mock(GitRepositoryManager.class);

    private WorkspaceFolderRenderer renderer() {
        when(policies.current())
                .thenReturn(new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()).current());
        return new WorkspaceFolderRenderer(
                mock(WorkspaceScmProjection.class),
                mapper,
                documents,
                mock(ConversationThreadProjection.class),
                policies,
                git,
                new JobEvidenceFiles(
                        new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC()),
                repositories,
                mock(ReviewHistoryContentSource.class),
                mock(ReviewMemberAiPolicy.class),
                mock(WorkspaceMembershipRepository.class),
                mock(PracticeRepository.class));
    }

    private AgentJob job() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setWorkspace(workspace);
        job.setWorkerId("worker");
        return job;
    }

    @Test
    void rendersEveryPermittedDocumentAndReportsEvictionWithoutRelevanceCaps() throws Exception {
        var source = renderer();
        var kind = new SourceKind("outline.documents");
        when(policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var pages = new java.util.ArrayList<DocumentProjection.ProjectedDocument>();
        for (int n = 0; n < 26; n++) {
            var page = mock(DocumentProjection.ProjectedDocument.class);
            when(page.collectionSlug()).thenReturn("engineering");
            when(page.slug()).thenReturn("page-" + n);
            when(page.sourceId()).thenReturn("id-" + n);
            when(page.syncedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
            when(page.bodyMarkdown()).thenReturn("Paragraph " + n);
            pages.add(page);
        }
        var evicted = mock(DocumentProjection.ProjectedDocument.class);
        when(evicted.collectionSlug()).thenReturn("engineering");
        when(evicted.slug()).thenReturn("evicted");
        pages.add(evicted);
        when(documents.workspaceReadable(1L)).thenReturn(true);
        when(documents.documentsForWorkspace(1L)).thenReturn(pages);
        var captured = source.capture(new ContextRequest.PracticeReviewRequest(job()), Set.of(kind));
        try {
            assertThat(captured.files()).isEmpty();
            assertThat(captured.filesOnDisk()).hasSize(26).containsKey("context/docs/engineering/page-25.md");
            assertThat(Files.readString(captured.filesOnDisk().get("context/docs/engineering/page-25.md")))
                    .contains("synced_at: 2026-10-01T00:00:00Z", "source_id: \"id-25\"", "Paragraph 25");
            assertThat(captured.refusals())
                    .contains(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.RECORD,
                            "docs/engineering/evicted",
                            SourceAbsenceReason.CONTENT_EVICTED));
            assertThat(captured.refusals())
                    .contains(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.AREA, "chat", SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }

    @Test
    void refusedRepositoriesHaveTypedReasonsAndNoBytes() throws Exception {
        var source = renderer();
        var tree = new SourceKind("scm.repository.tree");
        when(policies.isSourceUsePermitted(
                        policies.current().version(), tree, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var permitted = new Repository();
        permitted.setId(2L);
        permitted.setNameWithOwner("org/allowed");
        permitted.setDefaultBranch("main");
        var foreign = new Repository();
        foreign.setId(3L);
        when(repositories.monitoredRepositories(1L)).thenReturn(List.of(permitted, foreign));
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(permitted));
        var captured = source.capture(new ContextRequest.PracticeReviewRequest(job()), Set.of(tree));
        try {
            assertThat(captured.filesOnDisk()).isEmpty();
            assertThat(captured.directories()).isEmpty();
            assertThat(captured.refusals())
                    .contains(
                            new WorkspaceRefusal(
                                    WorkspaceRefusal.Target.REPOSITORY, "3", SourceAbsenceReason.ACCESS_NOT_PERMITTED),
                            new WorkspaceRefusal(
                                    WorkspaceRefusal.Target.REPOSITORY, "2", SourceAbsenceReason.NO_WORKING_COPY));
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }

    @Test
    void rendersPinnedCommitsWithParentsAndFileChangesForExistingPrecomputeConsumers() throws Exception {
        var source = renderer();
        var kind = PullRequestContentSource.CORE;
        when(policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var job = job();
        var key = new de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey(1L, 2L);
        var pinned = new ReviewRepositoryPreparer.PreparedReview(key, "head", "base");
        when(repositories.prepare(job)).thenReturn(pinned);
        var preparation = new de.tum.cit.aet.hephaestus.agent.context.ReviewPreparation();
        preparation.prepare(repositories, job);
        var changed = new de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails.FileChange(
                "new.txt",
                de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType.RENAMED,
                2,
                1,
                3,
                "old.txt");
        var commit = new de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails(
                "head",
                "Subject",
                "Details",
                "Author",
                "author@example.com",
                Instant.EPOCH,
                "Committer",
                "committer@example.com",
                Instant.EPOCH,
                2,
                1,
                1,
                List.of(changed),
                List.of("base"));
        when(git.commitsBetween(key, "base", "head")).thenReturn(List.of(commit));
        var captured = source.capture(new ContextRequest.PracticeReviewRequest(job, preparation), Set.of(kind));
        try {
            var data = mapper.readTree(
                    Files.readString(captured.filesOnDisk().get(PullRequestContentSource.COMMITS_FILE)));
            var record = data.path("commits").get(0);
            assertThat(record.path("message").asString()).isEqualTo("Subject\n\nDetails");
            assertThat(record.path("parents").get(0).asString()).isEqualTo("base");
            assertThat(record.path("files").get(0).path("status").asString()).isEqualTo("R");
            assertThat(record.path("files").get(0).path("oldPath").asString()).isEqualTo("old.txt");
            assertThat(record.has("synced_at")).isTrue();
            assertThat(source.sourceKindFor(PullRequestContentSource.COMMITS_FILE))
                    .isEqualTo(kind);
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }

    @Test
    void refusedDocumentOriginIsUnavailableNotAnExhaustiveEmptyCorpus() throws Exception {
        var source = renderer();
        var kind = new SourceKind("outline.documents");
        when(policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var captured = source.capture(new ContextRequest.PracticeReviewRequest(job()), Set.of(kind));
        try {
            assertThat(captured.filesOnDisk()).isEmpty();
            assertThat(captured.completeness()).doesNotContainKey(kind);
            assertThat(captured.stateOverrides().get(kind))
                    .isEqualTo(new de.tum.cit.aet.hephaestus.evidence.SourceCaptureState.Unavailable(
                            SourceAbsenceReason.ACCESS_NOT_PERMITTED));
            assertThat(captured.refusals())
                    .contains(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.AREA, "docs", SourceAbsenceReason.ACCESS_NOT_PERMITTED));
            org.mockito.Mockito.verify(documents, org.mockito.Mockito.never()).documentsForWorkspace(1L);
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }
}
