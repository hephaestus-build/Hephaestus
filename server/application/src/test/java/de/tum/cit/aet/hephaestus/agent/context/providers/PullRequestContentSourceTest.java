package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.MergeStateStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.ReviewDecision;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
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
    private ReviewRepositoryPreparer repositoryPreparer;

    private static final Long WORKSPACE_ID = 99L;
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");
    private static final String HEAD = "abc123def456";
    private static final String BASE = "a".repeat(40);

    private PullRequestContentSource provider;

    @BeforeEach
    void setUp() {
        lenient().when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(new PullRequest()));
        provider = new PullRequestContentSource(
                objectMapper, gitRepositoryManager, pullRequestRepository, reviewCommentRepository, repositoryPreparer);
    }

    private static final RepositoryKey REPOSITORY = new RepositoryKey(WORKSPACE_ID, 123L);
    private static final ReviewRepositoryPreparer.PreparedReview PREPARED =
            new ReviewRepositoryPreparer.PreparedReview(REPOSITORY, HEAD, BASE);

    private ObjectNode sampleMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pull_request_id", 456L);
        metadata.put("pr_number", 42);
        metadata.put("pr_url", "https://github.com/owner/repo/pull/42");
        metadata.put("commit_sha", HEAD);
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        metadata.put("base_ref_oid", BASE);
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

    private static Label label(String name) {
        Label label = new Label();
        label.setName(name);
        return label;
    }

    private static User user(String login) {
        User user = new User();
        user.setLogin(login);
        return user;
    }

    private void stubGit() {
        lenient().when(repositoryPreparer.prepare(any())).thenReturn(PREPARED);
        lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
        lenient().when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(true);
        lenient()
                .when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD))
                .thenReturn(Set.of("a.txt"));
        lenient()
                .when(gitRepositoryManager.commitsBetween(REPOSITORY, BASE, HEAD))
                .thenReturn(List.of());
    }

    private Map<String, byte[]> captureFiles(ContextRequest request) {
        return provider.capture(request, provider.sourceKinds()).files();
    }

    @Nested
    class Supports {

        @Test
        void supportsPracticeReview() {
            assertThat(provider.supports(request(sampleMetadata()))).isTrue();
        }
    }

    @Nested
    class SourceKinds {

        @Test
        void shouldMapEveryStagedFileToItsSourceKind() {
            assertThat(provider.sourceKindFor("inputs/context/metadata.json")).isEqualTo(CORE);
            assertThat(provider.sourceKindFor("inputs/context/comments.json")).isEqualTo(COMMENTS);
            assertThat(provider.sourceKindFor(PullRequestContentSource.CHANGE_FILE))
                    .isEqualTo(DIFF);
            assertThat(PullRequestContentSource.CHANGE_FILE).isEqualTo("inputs/context/change.json");
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
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

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
            pr.setState(Issue.State.MERGED);
            pr.setAdditions(10);
            pr.setCreatedAt(Instant.parse("2026-04-09T12:39:13Z"));
            pr.setMergedAt(Instant.parse("2026-04-09T14:47:18Z"));
            User author = new User();
            author.setLogin("testuser");
            pr.setAuthor(author);

            stubGit();
            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(pr));
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode metadataJson = objectMapper.readTree(files.get("inputs/context/metadata.json"));
            assertThat(metadataJson.get("title").asString()).isEqualTo("Fix authentication bug");
            assertThat(metadataJson.get("author").asString()).isEqualTo("testuser");
            assertThat(metadataJson.get("additions").asInt()).isEqualTo(10);
            assertThat(metadataJson.get("created_at").asString()).isEqualTo("2026-04-09T12:39:13Z");
            assertThat(metadataJson.get("merged_at").asString()).isEqualTo("2026-04-09T14:47:18Z");
            assertThat(metadataJson.has("closed_at")).isFalse();
        }

        @Test
        void shouldWriteTheRecordsStateBesideItsPeopleAndLabelsWhenThePullRequestCarriesThem() throws Exception {
            PullRequest pr = new PullRequest();
            pr.setTitle("Fix authentication bug");
            pr.setState(Issue.State.CLOSED);
            pr.setMerged(true);
            pr.setLabels(Set.of(label("security"), label("backend")));
            pr.setAssignees(Set.of(user("zoe"), user("adam")));
            Milestone milestone = new Milestone();
            milestone.setTitle("v1.2");
            pr.setMilestone(milestone);
            pr.setMergeStateStatus(MergeStateStatus.CLEAN);
            pr.setReviewDecision(ReviewDecision.APPROVED);

            stubGit();
            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(pr));

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("inputs/context/metadata.json"));

            assertThat(metadataJson.get("state").asString()).isEqualTo("CLOSED");
            assertThat(metadataJson.get("is_merged").asBoolean()).isTrue();
            assertThat(metadataJson.get("labels").valueStream().map(JsonNode::asString))
                    .containsExactly("backend", "security");
            assertThat(metadataJson.get("assignees").valueStream().map(JsonNode::asString))
                    .containsExactly("adam", "zoe");
            assertThat(metadataJson.get("milestone").asString()).isEqualTo("v1.2");
            assertThat(metadataJson.get("merge_state_status").asString()).isEqualTo("CLEAN");
            assertThat(metadataJson.get("review_decision").asString()).isEqualTo("APPROVED");
        }

        @Test
        void shouldMarkTheAuthorBotWhenTheAdapterStoredItAsOne() throws Exception {
            PullRequest pr = new PullRequest();
            User token = user("group_12_bot_9f3a");
            token.setType(User.Type.BOT);
            pr.setAuthor(token);
            stubGit();
            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(pr));

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("inputs/context/metadata.json"));

            assertThat(metadataJson.get("author").asString()).isEqualTo("group_12_bot_9f3a");
            assertThat(metadataJson.get("author_bot").asBoolean()).isTrue();
        }

        @Test
        void shouldWriteTheHeadChecksOnlyWhenTheyWereObservedForTheCurrentHead() throws Exception {
            PullRequest current = new PullRequest();
            current.setHeadRefOid(HEAD);
            current.observeHeadChecks(HEAD, CheckState.FAILURE, true);
            PullRequest stale = new PullRequest();
            stale.setHeadRefOid(HEAD);
            stale.observeHeadChecks("e".repeat(40), CheckState.SUCCESS, true);
            stubGit();

            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(current));
            JsonNode fresh = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("inputs/context/metadata.json"));
            assertThat(fresh.get("head_checks").asString()).isEqualTo("FAILURE");

            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(stale));
            JsonNode outdated = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("inputs/context/metadata.json"));
            assertThat(outdated.has("head_checks")).isFalse();
        }

        @Test
        void shouldWriteEmptyListsAndNoKeysWhenThePullRequestCarriesNoneOfThem() throws Exception {
            stubGit();

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("inputs/context/metadata.json"));

            assertThat(metadataJson.get("is_merged").asBoolean()).isFalse();
            assertThat(metadataJson.has("author_bot")).isFalse();
            assertThat(metadataJson.get("labels")).isEmpty();
            assertThat(metadataJson.get("assignees")).isEmpty();
            assertThat(metadataJson.has("milestone")).isFalse();
            assertThat(metadataJson.has("merge_state_status")).isFalse();
            assertThat(metadataJson.has("review_decision")).isFalse();
            assertThat(metadataJson.has("head_checks")).isFalse();
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
            PullRequestReviewComment automated = new PullRequestReviewComment();
            automated.setPath("src/Other.java");
            automated.setLine(6);
            automated.setBody("Coverage dropped.");
            automated.setCreatedAt(Instant.parse("2025-06-02T12:00:00Z"));
            User token = user("project_7_bot_a1b2");
            token.setType(User.Type.BOT);
            automated.setAuthor(token);

            stubGit();
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of(full, minimal, automated));

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            JsonNode comments = objectMapper.readTree(files.get("inputs/context/comments.json"));
            assertThat(comments).hasSize(3);
            assertThat(comments.get(0).get("created_at").asString()).isEqualTo("2025-06-01T12:00:00Z");
            assertThat(comments.get(0).get("author").asString()).isEqualTo("reviewer");
            assertThat(comments.get(0).has("bot")).isFalse();
            assertThat(comments.get(1).get("author").asString()).isEqualTo("project_7_bot_a1b2");
            assertThat(comments.get(1).get("bot").asBoolean()).isTrue();
            assertThat(comments.get(2).has("author")).isFalse();
        }

        @Test
        void shouldNameTheThreadTheParentAndTheSideWhenACommentHasThem() throws Exception {
            PullRequestReviewThread thread = new PullRequestReviewThread();
            ReflectionTestUtils.setField(thread, "id", 70L);
            PullRequestReviewComment root = new PullRequestReviewComment();
            ReflectionTestUtils.setField(root, "id", 1L);
            root.setThread(thread);
            root.setPath("src/Main.java");
            root.setLine(10);
            root.setSide(PullRequestReviewComment.Side.LEFT);
            root.setOutdated(true);
            root.setBody("This branch was removed on purpose?");
            root.setCreatedAt(Instant.parse("2025-06-01T12:00:00Z"));
            PullRequestReviewComment reply = new PullRequestReviewComment();
            ReflectionTestUtils.setField(reply, "id", 2L);
            reply.setThread(thread);
            reply.setInReplyTo(root);
            reply.setPath("src/Main.java");
            reply.setLine(10);
            reply.setSide(PullRequestReviewComment.Side.UNKNOWN);
            reply.setOutdated(false);
            reply.setBody("Yes, see the description.");
            reply.setCreatedAt(Instant.parse("2025-06-01T13:00:00Z"));

            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of(reply, root));

            JsonNode comments = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                    .files()
                    .get("inputs/context/comments.json"));

            JsonNode first = comments.get(0);
            assertThat(first.propertyNames())
                    .containsExactlyInAnyOrder(
                            "id", "thread", "path", "line", "side", "outdated", "body", "created_at");
            assertThat(first.get("id").asLong()).isEqualTo(1L);
            assertThat(first.get("thread").asLong()).isEqualTo(70L);
            assertThat(first.get("side").asString()).isEqualTo("LEFT");
            assertThat(first.get("outdated").asBoolean()).isTrue();
            JsonNode second = comments.get(1);
            assertThat(second.get("in_reply_to").asLong()).isEqualTo(1L);
            assertThat(second.get("thread").asLong()).isEqualTo(70L);
            assertThat(second.has("side")).isFalse();
            assertThat(second.has("outdated")).isFalse();
        }

        @Test
        void shouldLeaveHephaestusOwnNotesOutOfTheCommentsItAsksFor() {
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            verify(reviewCommentRepository)
                    .findRecentHumanByPullRequestIdWithAuthor(eq(456L), eq("<!-- hephaestus"), any());
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
            Collections.reverse(comments);

            stubGit();
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(comments);

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

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
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(comments);
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.COMPLETE);

            comments.add(new PullRequestReviewComment());
            assertThat(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                            .completeness()
                            .get(COMMENTS))
                    .isEqualTo(SourceCompleteness.PARTIAL);
        }
    }

    @Nested
    class Commits {

        @Test
        void shouldStageTheCommitsOfTheChangeOldestFirstWithTheirMessagesAndFiles() throws Exception {
            stubGit();
            var first = new CommitDetails(
                    "1".repeat(40),
                    "docs: describe usage",
                    null,
                    "Ada",
                    "ada@example.com",
                    Instant.parse("2026-04-09T10:00:00Z"),
                    "Ada",
                    "ada@example.com",
                    Instant.parse("2026-04-09T10:00:00Z"),
                    2,
                    0,
                    1,
                    List.of(new CommitDetails.FileChange("README.md", ChangeType.MODIFIED, 2, 0, 2, null)),
                    List.of(BASE));
            var second = new CommitDetails(
                    "2".repeat(40),
                    "feat: move a to b",
                    "Closes #7",
                    "Ada",
                    "ada@example.com",
                    Instant.parse("2026-04-09T11:00:00Z"),
                    "Bot",
                    "bot@example.com",
                    Instant.parse("2026-04-09T11:30:00Z"),
                    1,
                    1,
                    1,
                    List.of(new CommitDetails.FileChange("b.txt", ChangeType.RENAMED, 1, 1, 2, "a.txt")),
                    List.of("1".repeat(40)));
            when(gitRepositoryManager.commitsBetween(REPOSITORY, BASE, HEAD)).thenReturn(List.of(first, second));

            var captured = provider.capture(request(sampleMetadata()), Set.of(CORE));

            assertThat(provider.sourceKindFor(PullRequestContentSource.COMMITS_FILE))
                    .isEqualTo(CORE);
            JsonNode root = objectMapper.readTree(captured.files().get(PullRequestContentSource.COMMITS_FILE));
            assertThat(root.propertyNames()).containsExactly("commits");
            JsonNode commits = root.get("commits");
            assertThat(commits).hasSize(2);
            JsonNode one = commits.get(0);
            assertThat(one.propertyNames())
                    .containsExactly(
                            "sha", "parents", "author", "authoredAt", "committer", "committedAt", "message", "files");
            assertThat(one.get("sha").asString()).isEqualTo("1".repeat(40));
            assertThat(one.get("parents").get(0).asString()).isEqualTo(BASE);
            assertThat(one.get("author").asString()).isEqualTo("Ada");
            assertThat(one.get("authoredAt").asString()).isEqualTo("2026-04-09T10:00:00Z");
            assertThat(one.get("message").asString()).isEqualTo("docs: describe usage");
            JsonNode readme = one.get("files").get(0);
            assertThat(readme.propertyNames()).containsExactly("path", "status", "additions", "deletions");
            assertThat(readme.get("path").asString()).isEqualTo("README.md");
            assertThat(readme.get("status").asString()).isEqualTo("M");
            assertThat(readme.get("additions").asInt()).isEqualTo(2);
            JsonNode two = commits.get(1);
            assertThat(two.get("message").asString()).isEqualTo("feat: move a to b\n\nCloses #7");
            assertThat(two.get("committer").asString()).isEqualTo("Bot");
            assertThat(two.get("committedAt").asString()).isEqualTo("2026-04-09T11:30:00Z");
            JsonNode renamed = two.get("files").get(0);
            assertThat(renamed.get("status").asString()).isEqualTo("R");
            assertThat(renamed.get("oldPath").asString()).isEqualTo("a.txt");
            assertThat(new String(captured.files().get(PullRequestContentSource.COMMITS_FILE), StandardCharsets.UTF_8))
                    .doesNotContain("@example.com");
        }

        @Test
        void shouldStageNoCommitsWhenTheCloneIsNotPrepared() {
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
            verify(gitRepositoryManager, never()).commitsBetween(any(), any(), any());
        }
    }

    @Nested
    class ReviewedChange {

        @Test
        void shouldPinTheReviewedChangeAsBaseAndHeadWithoutStagingAnyPatch() throws Exception {
            stubGit();

            var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

            JsonNode change = objectMapper.readTree(captured.files().get(PullRequestContentSource.CHANGE_FILE));
            assertThat(change.path("base_sha").asString()).isEqualTo(BASE);
            assertThat(change.path("head_sha").asString()).isEqualTo(HEAD);
            assertThat(captured.files().keySet()).containsExactly(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.filesOnDisk()).isEmpty();
            assertThat(captured.cleanup()).isNull();
            assertThat(captured.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
            assertThat(captured.contentStates().get(DIFF)).isEqualTo(SourceContentState.NON_EMPTY);
            verify(gitRepositoryManager, never()).unifiedDiff(any(), any(), any());
        }

        @Test
        void shouldCaptureEmptyDiffAsCompleteEmptyEvidence() {
            stubGit();
            when(gitRepositoryManager.changedPaths(REPOSITORY, BASE, HEAD)).thenReturn(Set.of());

            var contribution = provider.capture(request(sampleMetadata()), Set.of(DIFF));

            assertThat(contribution.files()).containsKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(contribution.contentStates().get(DIFF)).isEqualTo(SourceContentState.EMPTY);
            assertThat(contribution.completeness().get(DIFF)).isEqualTo(SourceCompleteness.COMPLETE);
            assertThat(contribution.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
        }

        @Test
        void shouldRefuseCaptureWhenPinnedRangeCannotBeResolved() {
            stubGit();
            when(repositoryPreparer.prepare(any()))
                    .thenThrow(new JobPreparationException("The pinned review diff range is unavailable"));
            assertThatThrownBy(() -> captureFiles(request(sampleMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("pinned review diff range is unavailable");
        }

        @Test
        void shouldNotPinAChangeWhenOnlyCommentsAreSelected() {
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
            assertThat(captured.immutableIdentities()).isEmpty();
            verify(repositoryPreparer).authorize(any());
            verify(repositoryPreparer, never()).prepare(any());
            verifyNoInteractions(gitRepositoryManager);
        }
    }

    @Nested
    class RepositoryAvailability {

        @Test
        void throwsWhenRepositoryMissing() {
            lenient().when(gitRepositoryManager.isEnabled()).thenReturn(true);
            when(gitRepositoryManager.isRepositoryCloned(REPOSITORY)).thenReturn(false);

            assertThatThrownBy(() -> captureFiles(request(sampleMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Repository is not available locally for evidence capture");
        }

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            assertThatThrownBy(() -> captureFiles(new ContextRequest.PracticeReviewRequest(job)))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("no metadata");
        }
    }

    @Test
    void shouldReportUnavailableThenAllowCaptureWhenArtifactReturns() {
        when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.empty());
        for (var kind : provider.sourceKinds()) {
            var captured = provider.capture(request(sampleMetadata()), Set.of(kind));
            assertThat(captured.files()).isEmpty();
            assertThat(captured.completeness()).isEmpty();
            assertThat(captured.contentStates()).isEmpty();
            assertThat(captured.stateOverrides())
                    .containsExactlyEntriesOf(
                            Map.of(kind, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND)));
        }
        verifyNoInteractions(reviewCommentRepository, gitRepositoryManager, repositoryPreparer);
        var artifact = new PullRequest();
        when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(artifact));
        var restored = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
        assertThat(restored.stateOverrides()).isEmpty();
        assertThat(restored.files()).isNotEmpty();
    }

    @Test
    void shouldSkipGitPreparationWhenTheParentIsUnavailable() {
        var deleted = new PullRequest();
        deleted.setDeletedAt(Instant.parse("2026-09-05T00:00:00Z"));
        when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(deleted));

        var captured = provider.capture(request(sampleMetadata()), Set.of(DIFF));

        assertThat(captured.stateOverrides())
                .containsEntry(DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        verifyNoInteractions(gitRepositoryManager, repositoryPreparer);
    }

    @Test
    void shouldPrepareTheRepositoryOnceForEverySourceOfOneRequest() {
        stubGit();
        var request = request(sampleMetadata());

        for (var kind : provider.sourceKinds()) provider.capture(request, Set.of(kind));

        verify(repositoryPreparer, times(1)).prepare(any());
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
        verify(repositoryPreparer, times(1)).prepare(any());
    }

    @Test
    void shouldPinCoreAndDiffToTheSameReviewRange() {
        stubGit();

        var captured = provider.capture(request(sampleMetadata()), Set.of(CORE, DIFF));

        assertThat(captured.files().keySet())
                .containsExactlyInAnyOrder(
                        "inputs/context/metadata.json",
                        PullRequestContentSource.DESCRIPTION_FILE,
                        PullRequestContentSource.COMMITS_FILE,
                        PullRequestContentSource.CHANGE_FILE);
        assertThat(provider.sourceKindFor(PullRequestContentSource.DESCRIPTION_FILE))
                .isEqualTo(CORE);
        assertThat(captured.immutableIdentities().get(CORE)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.immutableIdentities().get(CORE))
                .isEqualTo(captured.immutableIdentities().get(DIFF));
    }

    @Test
    void shouldPinAgainstThePreparedTargetRatherThanJobMetadata() {
        stubGit();
        var metadata = sampleMetadata();
        metadata.remove("base_ref_oid");

        var captured = provider.capture(request(metadata), Set.of(CORE));

        assertThat(captured.immutableIdentities().get(CORE)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.CHANGE_FILE);
    }
}
