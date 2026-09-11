package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.util.QuotedString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private ConnectionService connectionService;

    @Mock
    private ScmTokenSource scmTokenSource;

    private static final Long WORKSPACE_ID = 99L;
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    private static final GitDiffOperations.CommitLogEntry AUTHORED = new GitDiffOperations.CommitLogEntry(
            "a".repeat(40),
            "Extract the retry logic into a helper",
            "The upload and the download paths duplicated it.",
            Instant.parse("2026-06-01T10:00:00Z"),
            Instant.parse("2026-06-01T10:01:00Z"),
            1,
            3);

    private static final GitDiffOperations.CommitLogEntry MERGE = new GitDiffOperations.CommitLogEntry(
            "b".repeat(40),
            "Merge branch 'main' into feature/auth-fix",
            null,
            Instant.parse("2026-06-01T11:00:00Z"),
            Instant.parse("2026-06-01T11:00:00Z"),
            2,
            null);

    private PullRequestContentSource provider;

    @BeforeEach
    void setUp() {
        lenient().when(pullRequestRepository.existsByIdAndDeletedAtIsNull(456L)).thenReturn(true);
        lenient()
                .when(pullRequestRepository.findByIdWithAuthorAndRepository(456L))
                .thenReturn(Optional.of(new PullRequest()));
        lenient().when(scmTokenSource.kind()).thenReturn(IntegrationKind.GITLAB);
        provider = new PullRequestContentSource(
                objectMapper,
                gitRepositoryManager,
                pullRequestRepository,
                reviewCommentRepository,
                gitDiffOperations,
                connectionService,
                List.of(scmTokenSource));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) count++;
        return count;
    }

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
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        lenient().when(gitRepositoryManager.isRepositoryCloned(123L)).thenReturn(true);
        lenient()
                .when(gitRepositoryManager.getRepositoryPath(123L))
                .thenReturn(Path.of("/tmp/hephaestus-git-repos/123"));
        lenient().when(gitRepositoryManager.commitExists(123L, "abc123def456")).thenReturn(true);
        lenient()
                .when(gitDiffOperations.resolveDiffRange(
                        Path.of("/tmp/hephaestus-git-repos/123"), "main", "feature/auth-fix", "abc123def456"))
                .thenReturn(new String[] {"main", "abc123def456"});
        lenient()
                .when(gitDiffOperations.diff(Path.of("/tmp/hephaestus-git-repos/123"), "main", "abc123def456"))
                .thenReturn("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -0,0 +1 @@\n+content\n");
        lenient()
                .when(gitDiffOperations.diffStat(Path.of("/tmp/hephaestus-git-repos/123"), "main", "abc123def456"))
                .thenReturn(" a.txt | 1\n");
        lenient()
                .when(gitDiffOperations.commitLog(
                        Path.of("/tmp/hephaestus-git-repos/123"),
                        "main",
                        "abc123def456",
                        PullRequestContentSource.MAX_COMMITS))
                .thenReturn(new GitDiffOperations.CommitLog(List.of(AUTHORED, MERGE), false));
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
        void shouldFailTheRecordWhenTheCloneIsUnavailable() {
            when(gitRepositoryManager.isEnabled()).thenReturn(false);

            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), java.util.Set.of(CORE)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Git local storage is disabled");
            verifyNoInteractions(gitDiffOperations);
        }

        @Test
        void shouldWriteTheRecordAndItsCommitsWhenCoreIsCaptured() throws Exception {
            stubGit();

            EvidenceContribution contribution = provider.capture(request(sampleMetadata()), java.util.Set.of(CORE));

            assertThat(contribution.files())
                    .containsOnlyKeys("inputs/context/metadata.json", "inputs/context/commits.json");
            assertThat(contribution.completeness().get(CORE)).isEqualTo(SourceCompleteness.COMPLETE);
            JsonNode commits = objectMapper.readTree(contribution.files().get("inputs/context/commits.json"));
            assertThat(commits.get("truncated").asBoolean()).isFalse();
            assertThat(commits.get("commits")).hasSize(2);
            JsonNode authored = commits.get("commits").get(0);
            assertThat(authored.get("sha").asString()).isEqualTo("a".repeat(40));
            assertThat(authored.get("subject").asString()).isEqualTo("Extract the retry logic into a helper");
            assertThat(authored.get("body").asString()).isEqualTo("The upload and the download paths duplicated it.");
            assertThat(authored.get("authored_at").asString()).isEqualTo("2026-06-01T10:00:00Z");
            assertThat(authored.get("committed_at").asString()).isEqualTo("2026-06-01T10:01:00Z");
            assertThat(authored.get("parent_count").asInt()).isEqualTo(1);
            assertThat(authored.get("changed_files").asInt()).isEqualTo(3);
            JsonNode merge = commits.get("commits").get(1);
            assertThat(merge.get("parent_count").asInt()).isEqualTo(2);
            assertThat(merge.has("body")).isFalse();
            assertThat(merge.has("changed_files")).isFalse();
        }

        @Test
        void shouldReportThePartialRecordWhenTheCommitLogIsTruncated() throws Exception {
            stubGit();
            when(gitDiffOperations.commitLog(
                            Path.of("/tmp/hephaestus-git-repos/123"),
                            "main",
                            "abc123def456",
                            PullRequestContentSource.MAX_COMMITS))
                    .thenReturn(new GitDiffOperations.CommitLog(List.of(AUTHORED), true));

            EvidenceContribution contribution = provider.capture(request(sampleMetadata()), java.util.Set.of(CORE));

            assertThat(contribution.completeness().get(CORE)).isEqualTo(SourceCompleteness.PARTIAL);
            JsonNode commits = objectMapper.readTree(contribution.files().get("inputs/context/commits.json"));
            assertThat(commits.get("truncated").asBoolean()).isTrue();
        }

        @Test
        void shouldAbortWhenTheCommitLogCannotBeRead() {
            stubGit();
            when(gitDiffOperations.commitLog(
                            Path.of("/tmp/hephaestus-git-repos/123"),
                            "main",
                            "abc123def456",
                            PullRequestContentSource.MAX_COMMITS))
                    .thenReturn(null);

            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), java.util.Set.of(CORE)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Commit log could not be read");
        }

        @Test
        void writesMetadataJson() throws Exception {
            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

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
            provider.contribute(request(sampleMetadata()), files);

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
            provider.contribute(request(sampleMetadata()), files);

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
            provider.contribute(request(sampleMetadata()), files);

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

        private final String repoPath = "/tmp/hephaestus-git-repos/123";

        @Test
        void shouldIndexEveryFileOfThePatchExactlyOnce() {
            String diff = "diff --git a/src/A.java b/src/A.java\n"
                    + "--- a/src/A.java\n+++ b/src/A.java\n@@ -0,0 +1,2 @@\n+line a1\n+line a2\n"
                    + "diff --git a/src/B.java b/src/B.java\n"
                    + "--- a/src/B.java\n+++ b/src/B.java\n@@ -0,0 +1 @@\n+line b1\n";
            byte[] annotated =
                    GitDiffOperations.annotateDiffWithLineNumbers(diff).getBytes(StandardCharsets.UTF_8);
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("inputs/context/diff.patch", annotated);

            provider.computeAndStoreDiffSummary(files, diff);

            String summary = new String(files.get("inputs/context/diff_summary.md"), StandardCharsets.UTF_8);
            assertThat(summary).contains("**2 files changed**");
            assertThat(countOccurrences(summary, "src/A.java")).isOne();
            assertThat(countOccurrences(summary, "src/B.java")).isOne();
            assertThat(summary).contains("`diff.patch`").doesNotContain("[L1]");
            assertThat(files.get("inputs/context/diff.patch")).isEqualTo(annotated);
        }

        @ParameterizedTest
        @ValueSource(strings = {"foo b/bar", "quoted\"path.txt", "café.txt", "pipe|back`tick*.txt", "line\nbreak.txt"})
        void shouldPreserveGitPathsWithoutMarkdownInterpretation(String path) {
            String oldPath = QuotedString.GIT_PATH.quote("a/" + path);
            String newPath = QuotedString.GIT_PATH.quote("b/" + path);
            String diff = "diff --git " + oldPath + " " + newPath + "\n" + "--- " + oldPath + "\n+++ " + newPath
                    + "\n@@ -0,0 +1 @@\n+content\n";
            Map<String, byte[]> files = new LinkedHashMap<>();

            provider.computeAndStoreDiffSummary(files, diff);

            String summary = new String(files.get("inputs/context/diff_summary.md"), StandardCharsets.UTF_8);
            assertThat(summary)
                    .contains("**1 file changed**", "\n    " + objectMapper.writeValueAsString(path) + "\n\n");
        }

        @Test
        void shouldSummarizeRenamesDeletionsAndNonTextChanges() {
            String diff = "diff --git a/old.txt b/new.txt\n"
                    + "similarity index 100%\nrename from old.txt\nrename to new.txt\n"
                    + "diff --git a/deleted.txt b/deleted.txt\n"
                    + "deleted file mode 100644\n--- a/deleted.txt\n+++ /dev/null\n"
                    + "@@ -1 +0,0 @@\n-content\n"
                    + "diff --git a/logo.png b/logo.png\n"
                    + "index 1234567..abcdef0 100644\nBinary files a/logo.png and b/logo.png differ\n"
                    + "diff --git a/script.sh b/script.sh\nold mode 100644\nnew mode 100755\n";
            Map<String, byte[]> files = new LinkedHashMap<>();

            provider.computeAndStoreDiffSummary(files, diff);

            assertThat(new String(files.get("inputs/context/diff_summary.md"), StandardCharsets.UTF_8))
                    .contains(
                            "**4 files changed**",
                            "    \"new.txt\"",
                            "    \"deleted.txt\"",
                            "    \"logo.png\"",
                            "    \"script.sh\"")
                    .doesNotContain("/dev/null", "    \"old.txt\"");
        }

        @Test
        void shouldSummarizeAPatchWhoseOnlyProblemsAreWarnings() {
            // A trailing "\ No newline at end of file" on the removed side alone is a warning in JGit; the
            // file list is still trustworthy, and a review must not be lost to it.
            String diff = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n"
                    + "@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n";
            Map<String, byte[]> files = new LinkedHashMap<>();

            provider.computeAndStoreDiffSummary(files, diff);

            assertThat(new String(files.get("inputs/context/diff_summary.md"), StandardCharsets.UTF_8))
                    .contains("**1 file changed**", "    \"a.txt\"");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "not a patch",
                    "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -0,0 +1,2 @@\n+only one line\n"
                })
        void shouldRefuseMalformedDiffSummaryRatherThanInventAnEmptyChange(String diff) {
            Map<String, byte[]> files = new LinkedHashMap<>();

            assertThatThrownBy(() -> provider.computeAndStoreDiffSummary(files, diff))
                    .isInstanceOf(JobPreparationException.class);

            assertThat(files).doesNotContainKey("inputs/context/diff_summary.md");
        }

        @Test
        void shouldSummarizeAnEmptyDiffAsZeroFiles() {
            Map<String, byte[]> files = new LinkedHashMap<>();

            provider.computeAndStoreDiffSummary(files, "");

            assertThat(new String(files.get("inputs/context/diff_summary.md"), StandardCharsets.UTF_8))
                    .contains("**0 files changed**");
        }

        @Test
        void emptyDiff_isCapturedAsAvailableEmptyEvidence() {
            stubGit();
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(new String[] {"main", "abc123def456"});
            when(gitDiffOperations.diffStat(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn("");
            when(gitDiffOperations.diff(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn("   ");

            EvidenceContribution contribution = provider.capture(request(sampleMetadata()), java.util.Set.of(DIFF));

            assertThat(contribution.files()).doesNotContainKey("inputs/context/commits.json");
            assertThat(contribution.files().get("inputs/context/diff.patch")).isEmpty();
            assertThat(contribution.contentStates().get(DIFF)).isEqualTo(SourceContentState.EMPTY);
            assertThat(contribution.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
        }

        @Test
        void unreadableDiff_abortsInsteadOfStoringAnEmptyOne() {
            stubGit();
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(new String[] {"main", "abc123def456"});
            lenient()
                    .when(gitDiffOperations.diffStat(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn(null);
            // null is what an unresolved object, an I/O error, or the 20 MiB cap looks like.
            when(gitDiffOperations.diff(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn(null);

            assertThatThrownBy(() -> provider.capture(request(sampleMetadata()), java.util.Set.of(DIFF)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Diff could not be read");
        }

        @Test
        void headVerifiedButRangeUnresolvable_abortsWithJobPreparationException() {
            stubGit();
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitRepositoryManager.commitExists(123L, "abc123def456")).thenReturn(true);
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(null);

            assertThatThrownBy(() -> provider.contribute(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("all resolution strategies failed");
        }

        @Test
        void missingPinnedHead_abortsBeforeSandboxLaunch() {
            stubGit();
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitRepositoryManager.commitExists(123L, "abc123def456")).thenReturn(false);
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(null);

            assertThatThrownBy(() -> provider.contribute(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("pinned head commit is unavailable");
        }

        @Test
        void unexpectedGitError_abortsWithJobPreparationException() {
            stubGit();
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(new String[] {"main", "abc123def456"});
            lenient()
                    .when(gitDiffOperations.diffStat(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn("1 file changed");
            when(gitDiffOperations.diff(Path.of(repoPath), "main", "abc123def456"))
                    .thenThrow(new RuntimeException("git process crashed"));

            assertThatThrownBy(() -> provider.contribute(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Failed to pre-compute diff");
        }

        @Test
        void realDiff_writesAnnotatedPatchAndSummary() throws Exception {
            stubGit();
            when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());
            when(gitDiffOperations.resolveDiffRange(Path.of(repoPath), "main", "feature/auth-fix", "abc123def456"))
                    .thenReturn(new String[] {"main", "abc123def456"});
            when(gitDiffOperations.diffStat(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn("1 file changed");
            when(gitDiffOperations.diff(Path.of(repoPath), "main", "abc123def456"))
                    .thenReturn(
                            "diff --git a/src/A.java b/src/A.java\n--- a/src/A.java\n+++ b/src/A.java\n@@ -1,1 +1,2 @@\n context\n+added\n");

            Map<String, byte[]> files = new LinkedHashMap<>();
            provider.contribute(request(sampleMetadata()), files);

            assertThat(files).containsKey("inputs/context/diff.patch");
            assertThat(files).containsKey("inputs/context/diff_stat.txt");
            assertThat(files).containsKey("inputs/context/diff_summary.md");
            String patch = new String(files.get("inputs/context/diff.patch"), StandardCharsets.UTF_8);
            assertThat(patch).contains("[L2] +added");
        }

        @Test
        void fetchesProviderReviewRefForForkHead() {
            stubGit();
            when(connectionService.findActiveProviderKind(WORKSPACE_ID))
                    .thenReturn(Optional.of(IntegrationKind.GITLAB));
            when(scmTokenSource.serverUrl(WORKSPACE_ID)).thenReturn(Optional.of("https://scm.example"));
            when(scmTokenSource.accessToken(WORKSPACE_ID)).thenReturn(Optional.of("token"));
            when(scmTokenSource.reviewHeadRef(42)).thenReturn(Optional.of("refs/merge-requests/42/head"));
            when(gitRepositoryManager.fetchRemoteCommit(123L, "refs/merge-requests/42/head", "abc123def456", "token"))
                    .thenReturn(true);
            lenient()
                    .when(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(eq(456L), any()))
                    .thenReturn(List.of());

            provider.contribute(request(sampleMetadata()), new LinkedHashMap<>());

            verify(gitRepositoryManager).ensureRepository(123L, "https://scm.example/owner/repo.git", "token");
            verify(gitRepositoryManager)
                    .fetchRemoteCommit(123L, "refs/merge-requests/42/head", "abc123def456", "token");
        }
    }

    @Nested
    class RepositoryAvailability {

        @Test
        void throwsWhenRepositoryMissing() {
            lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.isRepositoryCloned(123L)).thenReturn(false);

            assertThatThrownBy(() -> provider.contribute(request(sampleMetadata()), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Repository is not available locally for evidence capture");
        }

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            assertThatThrownBy(() ->
                            provider.contribute(new ContextRequest.PracticeReviewRequest(job), new LinkedHashMap<>()))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("no metadata");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns(boolean tombstoned) {
        var artifact = new PullRequest();
        artifact.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L))
                .thenReturn(tombstoned ? Optional.of(artifact) : Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(sampleMetadata()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(reviewCommentRepository, gitDiffOperations, gitRepositoryManager);
        artifact.setDeletedAt(null);
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }

    @Test
    void shouldFetchTheCloneWhenOnlyCoreIsPrepared() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        when(gitRepositoryManager.isRepositoryCloned(123L)).thenReturn(true);
        when(connectionService.findActiveProviderKind(WORKSPACE_ID)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(scmTokenSource.serverUrl(WORKSPACE_ID)).thenReturn(Optional.of("https://scm.example"));
        when(scmTokenSource.accessToken(WORKSPACE_ID)).thenReturn(Optional.of("token"));

        provider.prepareCapture(request(sampleMetadata()), Set.of(CORE));

        verify(gitRepositoryManager).ensureRepository(123L, "https://scm.example/owner/repo.git", "token");
    }

    @Test
    void shouldSkipGitPreparationWhenTheParentIsUnavailable() {
        when(pullRequestRepository.existsByIdAndDeletedAtIsNull(456L)).thenReturn(false);

        provider.prepareCapture(request(sampleMetadata()), Set.of(DIFF));

        verifyNoInteractions(gitRepositoryManager, gitDiffOperations, connectionService);
    }

    @Test
    void shouldRecheckAvailabilityAfterGitPreparation() {
        when(gitRepositoryManager.isEnabled()).thenReturn(true);
        provider.prepareCapture(request(sampleMetadata()), Set.of(DIFF));
        var deleted = new PullRequest();
        deleted.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findByIdWithAuthorAndRepository(456L)).thenReturn(Optional.of(deleted));

        var captured = provider.capture(request(sampleMetadata()), Set.of(CORE));

        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(CORE, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }
}
