package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class PullRequestContentSourceTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private GitRepositoryManager gitRepositoryManager;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private PullRequestReviewCommentRepository reviewCommentRepository;

    @Mock
    private GitDiffOperations gitDiffOperations;

    @Mock
    private ReviewRepositoryPreparer repositoryPreparer;

    private static final Long WORKSPACE_ID = 99L;
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    private PullRequestContentSource provider;

    @BeforeEach
    void setUp() {
        lenient()
                .when(pullRequestRepository.findByIdWithAuthorAndRepository(456L))
                .thenReturn(Optional.of(new PullRequest()));
        provider = new PullRequestContentSource(
                objectMapper,
                gitRepositoryManager,
                pullRequestRepository,
                reviewCommentRepository,
                gitDiffOperations,
                repositoryPreparer);
    }

    @TempDir
    Path stagingRoot;

    private static final RepositoryKey REPOSITORY = new RepositoryKey(WORKSPACE_ID, 123L);
    private static final ReviewRepositoryPreparer.PreparedReview PREPARED =
            new ReviewRepositoryPreparer.PreparedReview(REPOSITORY, "abc123def456", "a".repeat(40));

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pull_request_id", 456L);
        metadata.put("pr_number", 42);
        metadata.put("pr_url", "https://github.com/owner/repo/pull/42");
        metadata.put("commit_sha", "abc123def456");
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        metadata.put("base_ref_oid", "a".repeat(40));
        return metadata;
    }

    private AgentJob jobWith(ObjectNode metadata) {
        var job = new AgentJob();
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private ContextRequest.PracticeReviewRequest request(ObjectNode metadata) {
        return new ContextRequest.PracticeReviewRequest(jobWith(metadata));
    }

    private void stubGit() {
        lenient().when(repositoryPreparer.prepare(any())).thenReturn(PREPARED);
        lenient()
                .when(gitDiffOperations.captureCommits(REPOSITORY, "a".repeat(40), "abc123def456"))
                .thenAnswer(invocation -> commitCapture());
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        lenient().when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(true);
        lenient()
                .when(gitDiffOperations.resolveDiffRange(REPOSITORY, "a".repeat(40), "abc123def456"))
                .thenReturn(new String[] {"a".repeat(40), "abc123def456"});
        lenient()
                .when(gitDiffOperations.capture(REPOSITORY, "a".repeat(40), "abc123def456"))
                .thenAnswer(invocation -> diffCapture(
                        "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -0,0 +1 @@\n[L1] +content\n",
                        " a.txt | 1\n",
                        "**1 file changed**\n"));
    }

    private GitDiffOperations.CommitCapture commitCapture() {
        try {
            Path file = Files.createTempFile(stagingRoot, "commits-", ".json");
            Files.writeString(file, "{\"commits\":[],\"truncated\":false}");
            return new GitDiffOperations.CommitCapture(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private GitDiffOperations.DiffCapture diffCapture(String patch, String stat, String summary) {
        try {
            Path directory = Files.createTempDirectory(stagingRoot, "diff-");
            Map<String, Path> files = new LinkedHashMap<>();
            for (var entry : Map.of("diff.patch", patch, "diff_stat.txt", stat, "diff_summary.md", summary)
                    .entrySet()) {
                Path file = directory.resolve(entry.getKey());
                Files.writeString(file, entry.getValue());
                files.put(entry.getKey(), file);
            }
            return new GitDiffOperations.DiffCapture(directory, files);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void captureFiles(ContextRequest request, Map<String, byte[]> files) {
        var contribution = provider.capture(request, provider.sourceKinds());
        try {
            files.putAll(contribution.files());
            for (var entry : contribution.filesOnDisk().entrySet())
                files.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (contribution.cleanup() != null) {
                try {
                    contribution.cleanup().close();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
    }

    @Nested
    class Supports {

        @Test
        void supportsPracticeReview() {
            assertThat(provider.supports(request(sampleMetadata()))).isTrue();
        }
    }

    @Nested
    class MetadataAndComments {

        @Test
        void shouldRefuseCoreWithoutGitBecauseItsCommitHistoryIsUnavailable() {
            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), Set.of(CORE)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Git local storage is disabled");
        }

        @Test
        void writesMetadataJson() throws Exception {
            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = new LinkedHashMap<>();
            captureFiles(request(sampleMetadata()), files);

            assertThat(files).containsKey("inputs/context/metadata.json");
            JsonNode metadataJson = objectMapper.readTree(files.get("inputs/context/metadata.json"));
            assertThat(metadataJson.get("pr_number").asInt()).isEqualTo(42);
            assertThat(metadataJson.get("repository_full_name").asString()).isEqualTo("owner/repo");
        }

        @Test
        void enrichesFromDb() throws Exception {
            PullRequest pr = new PullRequest();
            pr.setTitle("Fix authentication bug");
            pr.setBody("This PR fixes the login issue");
            pr.setState(Issue.State.OPEN);
            pr.setAdditions(10);
            User author = new User();
            author.setLogin("testuser");
            pr.setAuthor(author);

            stubGit();
            when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(pr));
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = new LinkedHashMap<>();
            captureFiles(request(sampleMetadata()), files);

            JsonNode metadataJson = objectMapper.readTree(files.get("inputs/context/metadata.json"));
            assertThat(metadataJson.get("title").asString()).isEqualTo("Fix authentication bug");
            assertThat(metadataJson.get("author").asString()).isEqualTo("testuser");
            assertThat(metadataJson.get("additions").asInt()).isEqualTo(10);
        }

        @Test
        void writesCommentsJson() throws Exception {
            PullRequestReviewComment full = new PullRequestReviewComment();
            full.setPath("src/Main.java");
            full.setLine(10);
            full.setBody("Fix this");
            full.setCreatedAt(Instant.parse("2025-06-01T12:00:00Z"));
            User reviewer = new User();
            reviewer.setLogin("reviewer");
            full.setAuthor(reviewer);

            PullRequestReviewComment minimal = new PullRequestReviewComment();
            minimal.setPath("src/Other.java");
            minimal.setLine(5);
            minimal.setBody("Old comment");

            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of(full, minimal));

            Map<String, byte[]> files = new LinkedHashMap<>();
            captureFiles(request(sampleMetadata()), files);

            JsonNode comments = objectMapper.readTree(files.get("inputs/context/comments.json"));
            assertThat(comments).hasSize(2);
            assertThat(comments.get(0).get("created_at").asString()).isEqualTo("2025-06-01T12:00:00Z");
            assertThat(comments.get(0).get("author").asString()).isEqualTo("reviewer");
            assertThat(comments.get(1).has("author")).isFalse();
        }

        @Test
        void truncatesComments() throws Exception {
            var comments = new ArrayList<PullRequestReviewComment>();
            for (int i = 0; i < PullRequestContentSource.MAX_COMMENTS + 100; i++) {
                PullRequestReviewComment c = new PullRequestReviewComment();
                c.setPath("file.java");
                c.setLine(i);
                c.setBody("Comment " + i);
                c.setCreatedAt(Instant.EPOCH.plusSeconds(i));
                comments.add(c);
            }
            java.util.Collections.reverse(comments);

            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(comments);

            Map<String, byte[]> files = new LinkedHashMap<>();
            captureFiles(request(sampleMetadata()), files);

            JsonNode commentsJson = objectMapper.readTree(files.get("inputs/context/comments.json"));
            assertThat(commentsJson).hasSize(PullRequestContentSource.MAX_COMMENTS);
            assertThat(commentsJson.get(0).get("body").asString()).isEqualTo("Comment 100");
        }

        @Test
        void reportsExactLimitAsCompleteAndOverflowAsPartial() {
            List<PullRequestReviewComment> comments = new ArrayList<>();
            for (int i = 0; i < PullRequestContentSource.MAX_COMMENTS; i++) {
                PullRequestReviewComment comment = new PullRequestReviewComment();
                comment.setBody("Comment " + i);
                comments.add(comment);
            }
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(comments);
            assertThat(provider.capture(request(sampleMetadata()), java.util.Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.COMPLETE);

            comments.add(new PullRequestReviewComment());
            assertThat(provider.capture(request(sampleMetadata()), java.util.Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.PARTIAL);
        }
    }

    @Nested
    class DiffPrecompute {
        @Test
        void shouldCaptureEmptyDiffAsCompleteEmptyEvidence() throws Exception {
            stubGit();
            var diff = diffCapture("", "", "**0 files changed**\n");
            when(gitDiffOperations.capture(REPOSITORY, "a".repeat(40), "abc123def456"))
                    .thenReturn(diff);
            var contribution = provider.capture(request(sampleMetadata()), Set.of(DIFF));
            var cleanup = java.util.Objects.requireNonNull(contribution.cleanup());
            try {
                assertThat(contribution.files()).doesNotContainKey("inputs/context/diff.patch");
                assertThat(contribution.filesOnDisk().get("inputs/context/diff.patch"))
                        .hasContent("");
                assertThat(contribution.contentStates().get(DIFF)).isEqualTo(SourceContentState.EMPTY);
                assertThat(contribution.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            } finally {
                cleanup.close();
            }
            assertThat(diff.directory()).doesNotExist();
        }

        @Test
        void shouldRefuseCaptureWhenNativeDiffFails() {
            stubGit();
            when(gitDiffOperations.capture(REPOSITORY, "a".repeat(40), "abc123def456"))
                    .thenThrow(new JobPreparationException("Native Git failed"));
            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), Set.of(DIFF)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasRootCauseMessage("Native Git failed");
        }

        @Test
        void shouldRefuseCaptureWhenPinnedRangeCannotBeResolved() {
            stubGit();
            when(gitDiffOperations.resolveDiffRange(REPOSITORY, "a".repeat(40), "abc123def456"))
                    .thenReturn(null);
            assertThatThrownBy(() -> captureFiles(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("pinned review diff range is unavailable");
        }

        @Test
        void shouldReleaseNativeDiffWhenStagingCannotBeRead() throws Exception {
            stubGit();
            var diff = diffCapture("patch", "stat", "summary");
            Files.delete(diff.directory().resolve("diff.patch"));
            when(gitDiffOperations.capture(REPOSITORY, "a".repeat(40), "abc123def456"))
                    .thenReturn(diff);
            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), Set.of(DIFF)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Could not stage reviewed change");
            assertThat(diff.directory()).doesNotExist();
        }

        @Test
        void shouldForwardNativeAnnotatedPatchStatAndSummaryWithoutRewriting() {
            stubGit();
            String patch =
                    "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n@@ -1,1 +1,2 @@\n[L1]  context\n[L2] +added\n";
            String stat = " src/A.java | 1 +\n";
            String summary = "**1 file changed**\n\n    \"src/A.java\"\n";
            var diff = diffCapture(patch, stat, summary);
            when(gitDiffOperations.capture(REPOSITORY, "a".repeat(40), "abc123def456"))
                    .thenReturn(diff);
            Map<String, byte[]> files = new LinkedHashMap<>();
            captureFiles(request(sampleMetadata()), files);
            assertThat(files.get("inputs/context/diff.patch")).isEqualTo(patch.getBytes(StandardCharsets.UTF_8));
            assertThat(files.get("inputs/context/diff_stat.txt")).isEqualTo(stat.getBytes(StandardCharsets.UTF_8));
            assertThat(files.get("inputs/context/diff_summary.md")).isEqualTo(summary.getBytes(StandardCharsets.UTF_8));
            assertThat(diff.directory()).doesNotExist();
        }
    }

    @Nested
    class RepositoryAvailability {

        @Test
        void throwsWhenRepositoryMissing() {
            lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(false);

            assertThatThrownBy(() -> captureFiles(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Repository is not available locally for evidence capture");
        }

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            assertThatThrownBy(() -> captureFiles(new ContextRequest.PracticeReviewRequest(job), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("no metadata");
        }
    }

    @Test
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns() {
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(sampleMetadata()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(reviewCommentRepository, gitDiffOperations, repositoryPreparer);
        var artifact = new PullRequest();
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }

    @Test
    void shouldSkipGitPreparationWhenTheParentIsUnavailable() {
        var deleted = new PullRequest();
        deleted.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(deleted));

        var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

        assertThat(captured.stateOverrides())
                .containsEntry(DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        verifyNoInteractions(gitRepositoryManager, gitDiffOperations, repositoryPreparer);
    }

    @Test
    void shouldPrepareTheRepositoryOnceForEverySourceOfOneRequest() {
        stubGit();
        var request = request(sampleMetadata());

        for (var kind : provider.sourceKinds()) provider.capture(request, Set.of(kind));

        org.mockito.Mockito.verify(repositoryPreparer, org.mockito.Mockito.times(1))
                .prepare(any());
    }

    @Test
    void shouldHandTheSamePreparationFailureToEverySourceOfOneRequest() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(repositoryPreparer.prepare(any()))
                .thenThrow(new JobPreparationException("SCM credentials are unavailable"));
        var request = request(sampleMetadata());

        for (var kind : List.of(CORE, DIFF)) {
            assertThatThrownBy(() -> provider.capture(request, Set.of(kind)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessage("SCM credentials are unavailable");
        }
        org.mockito.Mockito.verify(repositoryPreparer, org.mockito.Mockito.times(1))
                .prepare(any());
    }

    @Test
    void shouldCaptureCoreCommitHistoryOnDiskAndUseTheSamePinnedRangeAsTheDiff() throws Exception {
        stubGit();
        var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));
        assertThat(captured.files()).doesNotContainKey("inputs/context/commits.json");
        Path commits = java.util.Objects.requireNonNull(captured.filesOnDisk().get("inputs/context/commits.json"));
        assertThat(commits).isRegularFile();
        assertThat(captured.immutableIdentities().get(CORE))
                .isEqualTo(captured.immutableIdentities().get(DIFF));
        org.mockito.Mockito.verify(gitDiffOperations).resolveDiffRange(REPOSITORY, "a".repeat(40), "abc123def456");
        java.util.Objects.requireNonNull(captured.cleanup()).close();
        assertThat(commits).doesNotExist();
    }

    @Test
    void shouldDiffAgainstThePreparedTargetRatherThanJobMetadata() {
        stubGit();
        var metadata = sampleMetadata();
        metadata.remove("base_ref_oid");
        var captured = provider.capture(request(metadata), Set.of(CORE));
        assertThat(captured.immutableIdentities().get(CORE)).isEqualTo("a".repeat(40) + ":abc123def456");
        org.mockito.Mockito.verify(gitDiffOperations).resolveDiffRange(REPOSITORY, "a".repeat(40), "abc123def456");
    }
}
