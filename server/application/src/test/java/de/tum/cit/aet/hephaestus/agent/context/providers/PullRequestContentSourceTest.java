package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @Mock
    private DeliveredPullRequestCommentLookup deliveredCommentLookup;

    private PullRequestContentSource provider;

    @BeforeEach
    void setUp() {
        lenient()
                .when(deliveredCommentLookup.findForPullRequest(anyLong(), anyLong()))
                .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of()));
        lenient().when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(new PullRequest()));
        provider = new PullRequestContentSource(
                objectMapper,
                gitRepositoryManager,
                pullRequestRepository,
                reviewCommentRepository,
                repositoryPreparer,
                deliveredCommentLookup);
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
            assertThat(provider.sourceKindFor("context/metadata.json")).isEqualTo(CORE);
            assertThat(provider.sourceKindFor("context/comments.json")).isEqualTo(COMMENTS);
            assertThat(provider.sourceKindFor(PullRequestContentSource.CHANGE_FILE))
                    .isEqualTo(DIFF);
            assertThat(PullRequestContentSource.CHANGE_FILE).isEqualTo("context/change.json");
        }
    }

    @Nested
    class MetadataAndComments {

        @Test
        void shouldReadCoreFromTheSnapshotWithoutRequiringGit() {
            assertThat(provider.capture(request(sampleMetadata()), Set.of(CORE)).files())
                    .containsKey("context/metadata.json");
            verifyNoInteractions(deliveredCommentLookup);
        }

        @Test
        void writesMetadataJson() throws Exception {
            stubGit();
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            Map<String, byte[]> files = captureFiles(request(sampleMetadata()));

            assertThat(files).containsKey("context/metadata.json");
            JsonNode metadataJson = objectMapper.readTree(files.get("context/metadata.json"));
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

            JsonNode metadataJson = objectMapper.readTree(files.get("context/metadata.json"));
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
                    .get("context/metadata.json"));

            assertThat(metadataJson.path("subject_role").asString()).isEqualTo("AUTHOR");
            var reviewerMetadata = sampleMetadata();
            reviewerMetadata.put("subject_role", "REVIEWER");
            JsonNode reviewerCore = objectMapper.readTree(provider.capture(request(reviewerMetadata), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(reviewerCore.path("subject_role").asString()).isEqualTo("REVIEWER");
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
                    .get("context/metadata.json"));

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
                    .get("context/metadata.json"));
            assertThat(fresh.get("head_checks").asString()).isEqualTo("FAILURE");

            when(pullRequestRepository.findByIdForReviewContext(456L)).thenReturn(Optional.of(stale));
            JsonNode outdated = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));
            assertThat(outdated.has("head_checks")).isFalse();
        }

        @Test
        void shouldWriteEmptyListsAndNoKeysWhenThePullRequestCarriesNoneOfThem() throws Exception {
            stubGit();

            JsonNode metadataJson = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(CORE))
                    .files()
                    .get("context/metadata.json"));

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
        void shouldExcludeRecordedMarkerlessInlineFeedbackWithoutDroppingOtherComments() throws Exception {
            PullRequestReviewComment own = new PullRequestReviewComment();
            own.setNativeId(81L);
            own.setBody("Explain how to try the timer.");
            PullRequestReviewComment human = new PullRequestReviewComment();
            human.setNativeId(82L);
            human.setBody(own.getBody());
            PullRequestReviewComment unknownId = new PullRequestReviewComment();
            unknownId.setBody(own.getBody());
            when(deliveredCommentLookup.findForPullRequest(WORKSPACE_ID, 456L))
                    .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(82L), Set.of(81L)));
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of(own, human, unknownId));

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));
            JsonNode comments = objectMapper.readTree(captured.files().get("context/comments.json"));

            assertThat(comments).hasSize(2);
            assertThat(comments.get(0).path("native_id").asLong()).isEqualTo(82L);
            assertThat(comments.get(1).path("body").asString()).isEqualTo(own.getBody());
            assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
        }

        @Test
        void shouldKeepCompleteEmptyCaptureWhenOnlyRecordedInlineFeedbackExists() throws Exception {
            PullRequestReviewComment own = new PullRequestReviewComment();
            own.setNativeId(81L);
            own.setBody("Explain how to try the timer.");
            when(deliveredCommentLookup.findForPullRequest(WORKSPACE_ID, 456L))
                    .thenReturn(new DeliveredPullRequestCommentLookup.CommentIds(Set.of(), Set.of(81L)));
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of(own));

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(objectMapper.readTree(captured.files().get("context/comments.json")))
                    .isEmpty();
            assertThat(captured.completeness()).containsEntry(COMMENTS, SourceCompleteness.COMPLETE);
            assertThat(captured.contentStates()).containsEntry(COMMENTS, SourceContentState.EMPTY);
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

            JsonNode comments = objectMapper.readTree(files.get("context/comments.json"));
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
            root.setNativeId(61L);
            root.setCommitId("original-comment-head");
            root.setUpdatedAt(Instant.parse("2025-06-01T12:30:00Z"));
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
                    .get("context/comments.json"));

            JsonNode first = comments.get(0);
            assertThat(first.propertyNames())
                    .containsExactlyInAnyOrder(
                            "id",
                            "thread",
                            "path",
                            "line",
                            "side",
                            "outdated",
                            "native_id",
                            "commit_id",
                            "body",
                            "created_at",
                            "updated_at");
            assertThat(first.get("id").asLong()).isEqualTo(1L);
            assertThat(first.path("native_id").asLong()).isEqualTo(61L);
            assertThat(first.path("commit_id").asString()).isEqualTo("original-comment-head");
            assertThat(first.path("updated_at").asString()).isEqualTo("2025-06-01T12:30:00Z");
            assertThat(first.get("thread").asLong()).isEqualTo(70L);
            assertThat(first.get("side").asString()).isEqualTo("LEFT");
            assertThat(first.get("outdated").asBoolean()).isTrue();
            JsonNode second = comments.get(1);
            assertThat(second.get("in_reply_to").asLong()).isEqualTo(1L);
            assertThat(second.get("thread").asLong()).isEqualTo(70L);
            assertThat(second.has("side")).isFalse();
            assertThat(second.has("outdated")).isFalse();
        }

        @ParameterizedTest
        @CsvSource(
                value = {
                    "GITHUB, original-head, current-head, original-head",
                    "GITHUB, NULL, current-head, current-head",
                    "GITHUB, '', current-head, current-head",
                    "GITHUB, NULL, NULL, NULL",
                    "GITHUB, '', '', NULL",
                    "GITLAB, comparison-base, reviewed-head, reviewed-head"
                },
                nullValues = "NULL")
        void shouldKeepTheProvidersActualCommentRevision(
                IdentityProviderType type,
                @Nullable String original,
                @Nullable String current,
                @Nullable String expected)
                throws Exception {
            var comment = new PullRequestReviewComment();
            comment.setProvider(new IdentityProvider(type, "https://scm.example"));
            comment.setOriginalCommitId(original);
            comment.setCommitId(current);
            comment.setPath("src/Main.java");
            comment.setBody("Use the shared helper here.");
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of(comment));

            JsonNode captured = objectMapper.readTree(provider.capture(request(sampleMetadata()), Set.of(COMMENTS))
                    .files()
                    .get("context/comments.json"));
            if (expected == null) {
                assertThat(captured.get(0).has("commit_id")).isFalse();
            } else {
                assertThat(captured.get(0).path("commit_id").asString()).isEqualTo(expected);
            }
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
        void shouldKeepAllCommentsAboveTheFormerCaptureLimit() throws Exception {
            var comments = new ArrayList<PullRequestReviewComment>();
            for (int i = 0; i < 10_000 + 100; i++) {
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

            JsonNode commentsJson = objectMapper.readTree(files.get("context/comments.json"));
            assertThat(commentsJson).hasSize(comments.size());
            assertThat(commentsJson.get(0).get("body").asString()).isEqualTo("Comment 0");
        }

        @Test
        void shouldReportCompleteWithoutACommentLimit() {
            List<PullRequestReviewComment> comments = new ArrayList<>();
            for (int i = 0; i < 10_000; i++) {
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
                    .isEqualTo(SourceCompleteness.COMPLETE);
        }
    }

    @Nested
    class Commits {

        @Test
        void shouldStageNoCommitsWhenTheCloneIsNotPrepared() {
            when(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(eq(456L), any(), any()))
                    .thenReturn(List.of());

            var captured = provider.capture(request(sampleMetadata()), Set.of(COMMENTS));

            assertThat(captured.files()).doesNotContainKey(PullRequestContentSource.COMMITS_FILE);
            verify(gitRepositoryManager, never()).forEachCommitBetween(any(), any(), any(), any());
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

        for (var kind : List.of(DIFF, DIFF)) {
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
                        "context/metadata.json",
                        PullRequestContentSource.DESCRIPTION_FILE,
                        PullRequestContentSource.CHANGE_FILE);
        assertThat(provider.sourceKindFor(PullRequestContentSource.DESCRIPTION_FILE))
                .isEqualTo(CORE);
        assertThat(captured.immutableIdentities()).doesNotContainKey(CORE);
        assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
    }

    @Test
    void shouldPinAgainstThePreparedTargetRatherThanJobMetadata() {
        stubGit();
        var metadata = sampleMetadata();
        metadata.remove("base_ref_oid");

        var captured = provider.capture(request(metadata), Set.of(DIFF));

        assertThat(captured.immutableIdentities().get(DIFF)).isEqualTo(BASE + ":" + HEAD);
        assertThat(captured.files()).containsKey(PullRequestContentSource.CHANGE_FILE);
    }
}
