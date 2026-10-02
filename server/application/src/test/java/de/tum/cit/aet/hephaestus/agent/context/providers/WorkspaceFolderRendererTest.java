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
    private static de.tum.cit.aet.hephaestus.agent.context.EvidenceFolderPersonDataCatalog personCopies() {
        AutoCloseable released = () -> {};
        return org.mockito.Mockito.mock(
                de.tum.cit.aet.hephaestus.agent.context.EvidenceFolderPersonDataCatalog.class,
                invocation -> invocation.getMethod().getName().equals("finishCapture")
                        ? released
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    @TempDir
    Path root;

    private final JsonMapper mapper = new JsonMapper();
    private final ArtifactSourceCatalogRegistry policies = mock(ArtifactSourceCatalogRegistry.class);
    private final DocumentProjection documents = mock(DocumentProjection.class);
    private final ReviewRepositoryPreparer repositories = mock(ReviewRepositoryPreparer.class);
    private final GitRepositoryManager git = mock(GitRepositoryManager.class);

    private final WorkspaceScmProjection scm = mock(WorkspaceScmProjection.class);
    private final ConversationThreadProjection conversations = mock(ConversationThreadProjection.class);
    private final ReviewHistoryContentSource history = mock(ReviewHistoryContentSource.class);
    private final ReviewMemberAiPolicy memberPolicy = mock(ReviewMemberAiPolicy.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);

    private WorkspaceFolderRenderer renderer() {
        when(policies.current())
                .thenReturn(new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()).current());
        return new WorkspaceFolderRenderer(
                scm,
                mapper,
                documents,
                conversations,
                policies,
                git,
                new JobEvidenceFiles(
                        new FabricLayout(root.toString()),
                        mock(AgentJobRepository.class),
                        Clock.systemUTC(),
                        personCopies()),
                repositories,
                history,
                memberPolicy,
                memberships,
                practices,
                org.mockito.Mockito.mock(de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder.class));
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
    void mentorRequestsCannotUseTheReviewFolderRenderer() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                        renderer().capture(new ContextRequest.MentorChatRequest(1L, 42L, UUID.randomUUID()), Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No review job for mentor workspace 1");
        assertThat(new FabricLayout(root.toString()).jobsRoot()).doesNotExist();
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

    @Test
    void rendersCanonicalRecordsAndOmitsHiddenOrNonConsentingPeople() throws Exception {
        var source = renderer();
        Set<SourceKind> selected = Set.of(
                new SourceKind("workspace.project-inventory"),
                new SourceKind("slack.conversation.thread"),
                new SourceKind("hephaestus.observation-history"),
                new SourceKind("hephaestus.feedback-history"),
                PullRequestContentSource.CORE);
        for (var kind : selected)
            when(policies.isSourceUsePermitted(
                            policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .thenReturn(true);
        var repo = new Repository();
        repo.setId(2L);
        repo.setNameWithOwner("org/reviewed");
        repo.setDefaultBranch("main");
        repo.setLastSyncAt(Instant.EPOCH);
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(repo));
        var job = job();
        job.setMetadata(mapper.createObjectNode().put("repository_id", 2L));
        org.mockito.Mockito.doAnswer(call -> {
                    java.util.function.Consumer<WorkspaceScmProjection.ProjectedRecord> output = call.getArgument(3);
                    var record =
                            mapper.createObjectNode().put("body", "SCM prose").putNull("synced_at");
                    for (var format : WorkspaceScmProjection.Format.values())
                        output.accept(new WorkspaceScmProjection.ProjectedRecord(
                                "pulls/1/" + format.name(), PullRequestContentSource.CORE, format, record));
                    return null;
                })
                .when(scm)
                .forEachRecord(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(2L),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
                    java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> output = call.getArgument(1);
                    output.accept(mapper.createObjectNode()
                            .put("channel", "..")
                            .put("month", "2026-10")
                            .put("text", "chat quote")
                            .putNull("synced_at"));
                    output.accept(mapper.createObjectNode()
                            .put("channel", ".")
                            .put("month", "2026-10")
                            .put("text", "second quote")
                            .putNull("synced_at"));
                    return null;
                })
                .when(conversations)
                .forEachWorkspaceMessage(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any());
        var visible = new de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership();
        var user = new de.tum.cit.aet.hephaestus.integration.scm.domain.user.User();
        user.setId(42L);
        user.setLogin("author");
        visible.setUser(user);
        var denied = new de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership();
        var deniedUser = new de.tum.cit.aet.hephaestus.integration.scm.domain.user.User();
        deniedUser.setId(43L);
        denied.setUser(deniedUser);
        var hidden = new de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership();
        hidden.setHidden(true);
        when(memberships.findByWorkspace_Id(1L)).thenReturn(List.of(visible, denied, hidden));
        when(memberPolicy.allowsPerson(job, 42L)).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> {
                    java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> observations =
                            call.getArgument(4);
                    java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> feedback = call.getArgument(5);
                    observations.accept(mapper.createObjectNode()
                            .put("summary", "prior observation")
                            .putNull("synced_at"));
                    feedback.accept(mapper.createObjectNode()
                            .put("body", "prior feedback")
                            .putNull("synced_at"));
                    return null;
                })
                .when(history)
                .renderPersonHistory(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.anySet(),
                        org.mockito.ArgumentMatchers.eq(job.getId()),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var practice = new de.tum.cit.aet.hephaestus.practices.model.Practice();
        practice.setSlug("review quality");
        practice.setCriteria("Practice prose");
        practice.setUpdatedAt(Instant.EPOCH);
        when(practices.findByWorkspaceId(1L)).thenReturn(List.of(practice));
        var captured = source.capture(new ContextRequest.ConversationReviewRequest(job), selected);
        try {
            assertThat(captured.filesOnDisk())
                    .containsKeys(
                            "context/scm/reviewed/repository.json",
                            "context/scm/reviewed/pulls/1/JSON",
                            "context/scm/reviewed/pulls/1/JSONL",
                            "context/scm/reviewed/pulls/1/MARKDOWN",
                            "context/chat/%2E%2E/2026-10.jsonl",
                            "context/chat/%2E/2026-10.jsonl",
                            "context/people/42/person.json",
                            "context/people/42/observations.jsonl",
                            "context/people/42/feedback.jsonl",
                            "context/practices/review%20quality.md",
                            "context/project_inventory.json");
            assertThat(Files.readString(captured.filesOnDisk().get("context/people/42/observations.jsonl")))
                    .contains("prior observation", "synced_at");
            assertThat(Files.readString(captured.filesOnDisk().get("context/people/42/feedback.jsonl")))
                    .contains("prior feedback", "synced_at");
            assertThat(captured.filesOnDisk().keySet()).noneMatch(path -> path.startsWith("context/people/43/"));
            assertThat(captured.refusals())
                    .contains(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.RECORD, "people/43", SourceAbsenceReason.CONSENT_NOT_ACTIVE));
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void repositorySnapshotsAreCopiedOnlyWhenComplete(boolean complete) throws Exception {
        var source = renderer();
        var kind = new SourceKind("scm.repository.tree");
        when(policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var repo = new Repository();
        repo.setId(2L);
        repo.setDefaultBranch("main");
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(repo));
        var key = new de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey(1L, 2L);
        when(git.isEnabled()).thenReturn(true);
        when(git.isRepositoryCloned(key)).thenReturn(true);
        when(git.resolveBranchHead(key, "main")).thenReturn("head");
        Path snapshot = Files.createDirectory(root.resolve("snapshot"));
        Files.createDirectories(snapshot.resolve(".git"));
        Files.writeString(snapshot.resolve(".git/HEAD"), "head");
        Files.writeString(snapshot.resolve(".git/hephaestus-captured-refs"), "head");
        Files.writeString(snapshot.resolve("file.txt"), "repository quote");
        when(git.readTreeSnapshot(key, "head"))
                .thenReturn(
                        new GitRepositoryManager.GitTreeSnapshot(snapshot, "head", "tree", 100, 3, complete, Set.of()));
        var captured = source.capture(new ContextRequest.DocumentReviewRequest(job()), Set.of(kind));
        try {
            assertThat(snapshot).doesNotExist();
            if (complete) {
                assertThat(captured.directories()).hasSize(1);
                assertThat(Files.readString(
                                captured.directories().getFirst().source().resolve("file.txt")))
                        .isEqualTo("repository quote");
                assertThat(captured.filesOnDisk())
                        .containsKeys("repos/2/.git/HEAD", "repos/2/.git/hephaestus-captured-refs");
            } else {
                assertThat(captured.directories()).isEmpty();
                assertThat(captured.refusals())
                        .contains(new WorkspaceRefusal(
                                WorkspaceRefusal.Target.REPOSITORY, "2", SourceAbsenceReason.PROVIDER_FAILURE));
            }
        } finally {
            java.util.Objects.requireNonNull(captured.cleanup()).close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"oversized", "symlink"})
    void unsafeRepositorySnapshotRefusesRenderingAndDeletesBothCopies(String failure) throws Exception {
        var source = renderer();
        var kind = new SourceKind("scm.repository.tree");
        when(policies.isSourceUsePermitted(
                        policies.current().version(), kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        var repo = new Repository();
        repo.setId(2L);
        repo.setDefaultBranch("main");
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(repo));
        var key = new de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey(1L, 2L);
        when(git.isEnabled()).thenReturn(true);
        when(git.isRepositoryCloned(key)).thenReturn(true);
        when(git.resolveBranchHead(key, "main")).thenReturn("head");
        Path snapshot = Files.createDirectory(root.resolve("snapshot"));
        if (failure.equals("oversized")) {
            try (var file =
                    new java.io.RandomAccessFile(snapshot.resolve("blob").toFile(), "rw")) {
                file.setLength(
                        de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET + 1);
            }
        } else {
            Files.createSymbolicLink(snapshot.resolve("outside"), root.resolve("outside"));
        }
        when(git.readTreeSnapshot(key, "head"))
                .thenReturn(new GitRepositoryManager.GitTreeSnapshot(snapshot, "head", "tree", 0, 1, true, Set.of()));
        var job = job();
        var error = org.assertj.core.api.Assertions.catchThrowable(
                () -> source.capture(new ContextRequest.IssueReviewRequest(job), Set.of(kind)));
        if (failure.equals("oversized"))
            assertThat(error)
                    .isInstanceOf(de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException.class);
        else
            assertThat(error).isInstanceOf(java.io.UncheckedIOException.class).hasMessageContaining("non-regular file");
        assertThat(snapshot).doesNotExist();
        try (var remnants = Files.list(new FabricLayout(root.toString())
                .jobsRoot()
                .resolve("1")
                .resolve(job.getId().toString()))) {
            assertThat(remnants.toList()).isEmpty();
        }
    }
}
