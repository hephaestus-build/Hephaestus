package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import reactor.core.publisher.Mono;

/**
 * The review decisions GitLab writes as system notes, read by the discussion sync, with the notes' bodies as GitLab
 * writes them; and the GraphQL page loop, driven through scripted discussion pages, where a write conflicts.
 */
class GitLabDiscussionSyncServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);

    private static final long PROVIDER_ID = 2L;
    private static final Long SCOPE_ID = 100L;
    private static final String FULL_PATH = "hephaestustest/demo-repository";
    private static final String DOCUMENT = "GetMergeRequestDiscussions";

    private final Deque<ClientGraphQlResponse> scriptedResponses = new ArrayDeque<>();

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private GraphQlClient.RequestSpec requestSpec;

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabPullRequestReviewThreadProcessor threadProcessor;

    @Mock
    private GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;

    @Mock
    private GitLabIssueCommentProcessor issueCommentProcessor;

    @Mock
    private GitLabReviewReconciler reviewReconciler;

    @Mock
    private GitLabProperties gitLabProperties;

    private GitLabDiscussionSyncService service;
    private IdentityProvider provider;
    private PullRequest pr;
    private User reviewer;

    @BeforeEach
    void setUp() {
        service = new GitLabDiscussionSyncService(
                graphQlClientProvider,
                responseHandler,
                threadProcessor,
                reviewCommentProcessor,
                issueCommentProcessor,
                reviewReconciler,
                gitLabProperties,
                projectAdmission);
        provider = new IdentityProvider();
        provider.setId(PROVIDER_ID);
        pr = new PullRequest();
        pr.setNativeId(343218L);
        pr.setProvider(provider);
        reviewer = new User();
        reviewer.setNativeId(90002L);
        reviewer.setLogin("erika.maria.sample");
        lenient()
                .when(issueCommentProcessor.findOrCreateUser(any(), eq(PROVIDER_ID)))
                .thenReturn(reviewer);
    }

    private static Map<String, Object> systemNote(String id, String body, String createdAt) {
        return Map.of(
                "id",
                "gid://gitlab/Note/" + id,
                "body",
                body,
                "system",
                true,
                "createdAt",
                createdAt,
                "author",
                Map.of(
                        "id", "gid://gitlab/User/90002",
                        "username", "erika.maria.sample",
                        "name", "Erika Sample"));
    }

    @Test
    void shouldHandARequestedChangesNoteToTheReconcilerAsAReplayedNote() {
        service.recordReviewDecisionFromSystemNote(
                systemNote("4538627", "requested changes", "2026-04-15T10:23:58.766+02:00"), pr, PROVIDER_ID);

        verify(reviewReconciler)
                .recordSystemNote(
                        eq(pr),
                        eq(reviewer),
                        eq(new GitLabReviewReconciler.SystemNote(
                                "requested changes",
                                Instant.parse("2026-04-15T08:23:58.766Z"),
                                "gid://gitlab/Note/4538627",
                                false)),
                        eq(provider));
    }

    @Test
    void shouldIgnoreASystemNoteThatIsNotAReviewDecision() {
        service.recordReviewDecisionFromSystemNote(
                systemNote("5", "requested review from @jennifer.wagner", "2026-04-15T10:00:00+02:00"),
                pr,
                PROVIDER_ID);

        verifyNoInteractions(reviewReconciler);
    }

    @Test
    void shouldHandTheTypedConflictToTheMergeRequestSyncWithoutReadingTheNextPageWhenAGeneralNoteWriteConflicts() {
        GitLabDiscussionSyncService pages = pageDrivenService();
        scriptedResponses.add(discussionPage(List.of(generalDiscussion("a1", "601")), true));
        scriptedResponses.add(discussionPage(List.of(generalDiscussion("a2", "602")), false));
        var conflict = new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary");
        when(issueCommentProcessor.processFromSync(any(), any(), any(), any())).thenThrow(conflict);

        assertThatThrownBy(() -> pages.syncDiscussionsForMergeRequest(SCOPE_ID, repository(), 3, pr))
                .isSameAs(conflict);
        assertConflictStoppedTheRead();
    }

    @Test
    void shouldHandTheTypedConflictToTheMergeRequestSyncWithoutReadingTheNextPageWhenADiffThreadWriteConflicts() {
        GitLabDiscussionSyncService pages = pageDrivenService();
        scriptedResponses.add(discussionPage(List.of(diffDiscussion("d1", "701")), true));
        scriptedResponses.add(discussionPage(List.of(diffDiscussion("d2", "702")), false));
        var conflict = new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary");
        when(threadProcessor.findOrCreateThread(any(), any(), any(), any())).thenThrow(conflict);

        assertThatThrownBy(() -> pages.syncDiscussionsForMergeRequest(SCOPE_ID, repository(), 3, pr))
                .isSameAs(conflict);
        assertConflictStoppedTheRead();
    }

    @Test
    void shouldHandTheTypedConflictToTheMergeRequestSyncWhenRecordingTheThreadResolutionsConflicts() {
        GitLabDiscussionSyncService pages = pageDrivenService();
        scriptedResponses.add(discussionPage(List.of(), false));
        var conflict = new ObjectOptimisticLockingFailureException("private-repository-canary", "private-row-canary");
        doThrow(conflict).when(threadProcessor).applyDiscussionRead(any(), anyInt(), any(), any(), any(), any());

        assertThatThrownBy(() -> pages.syncDiscussionsForMergeRequest(SCOPE_ID, repository(), 3, pr))
                .isSameAs(conflict);
        assertThat(scriptedResponses).isEmpty();
        verify(graphQlClientProvider, never()).recordFailure(any());
    }

    @Test
    void shouldSkipTheNoteAndReadEveryPageWhenAGeneralNoteFailsForAnotherReason() {
        GitLabDiscussionSyncService pages = pageDrivenService();
        scriptedResponses.add(discussionPage(List.of(generalDiscussion("a1", "601")), true));
        scriptedResponses.add(discussionPage(List.of(generalDiscussion("a2", "602")), false));
        when(issueCommentProcessor.processFromSync(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("ordinary failure"));

        assertThat(pages.syncDiscussionsForMergeRequest(SCOPE_ID, repository(), 3, pr))
                .isZero();
        assertThat(scriptedResponses).isEmpty();
        verify(graphQlClientProvider, never()).recordFailure(any());
        verify(threadProcessor).applyDiscussionRead(any(), anyInt(), any(), any(), any(), any());
    }

    /** The conflict ended the read on its page: the next page stays unread, no resolution is recorded, and GitLab is not blamed. */
    private void assertConflictStoppedTheRead() {
        assertThat(scriptedResponses).hasSize(1);
        verify(graphQlClientProvider, never()).recordFailure(any());
        verify(threadProcessor, never()).applyDiscussionRead(any(), anyInt(), any(), any(), any(), any());
    }

    /** The service with the real response handling, reading the pages this test scripts. */
    private GitLabDiscussionSyncService pageDrivenService() {
        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(
                        invocation -> scriptedResponses.isEmpty() ? Mono.empty() : Mono.just(scriptedResponses.poll()));
        return new GitLabDiscussionSyncService(
                graphQlClientProvider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
                threadProcessor,
                reviewCommentProcessor,
                issueCommentProcessor,
                reviewReconciler,
                new GitLabProperties(
                        "https://gitlab.lrz.de",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)),
                projectAdmission);
    }

    private Repository repository() {
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        repository.setProvider(provider);
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);
        return repository;
    }

    private static Map<String, Object> note(String id, @Nullable Map<String, Object> position) {
        Map<String, Object> note = new HashMap<>(Map.of(
                "id",
                "gid://gitlab/Note/" + id,
                "body",
                "Please add a regression test.",
                "system",
                false,
                "internal",
                false,
                "createdAt",
                "2026-04-15T10:23:58.766+02:00",
                "updatedAt",
                "2026-04-15T10:23:58.766+02:00",
                "author",
                Map.of("id", "gid://gitlab/User/90002", "username", "erika.maria.sample")));
        if (position != null) {
            note.put("position", position);
        }
        return note;
    }

    private static Map<String, Object> discussion(String id, Map<String, Object> note) {
        return Map.of(
                "id",
                "gid://gitlab/Discussion/" + id,
                "resolved",
                false,
                "notes",
                Map.of("pageInfo", Map.of("hasNextPage", false), "nodes", List.of(note)));
    }

    private static Map<String, Object> generalDiscussion(String id, String noteId) {
        return discussion(id, note(noteId, null));
    }

    private static Map<String, Object> diffDiscussion(String id, String noteId) {
        return discussion(
                id,
                note(
                        noteId,
                        Map.of(
                                "filePath", "src/App.java",
                                "newPath", "src/App.java",
                                "oldPath", "src/App.java",
                                "newLine", 12,
                                "positionType", "text",
                                "diffRefs",
                                        Map.of(
                                                "baseSha", "a".repeat(40),
                                                "headSha", "b".repeat(40),
                                                "startSha", "a".repeat(40)))));
    }

    /** One page of merge request !3's discussions. */
    private static ClientGraphQlResponse discussionPage(List<Map<String, Object>> nodes, boolean hasNextPage) {
        assertVendorCouldReturn(GITLAB, DOCUMENT, "project.mergeRequest.discussions.nodes", nodes);
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", hasNextPage ? "next-page" : null);
        return GraphQlResponses.of(
                Map.of(
                        "project",
                        Map.of("mergeRequest", Map.of("discussions", Map.of("nodes", nodes, "pageInfo", pageInfo)))),
                List.of());
    }
}
