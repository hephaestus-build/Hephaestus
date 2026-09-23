package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectItemSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitHubPullRequestSyncService}.
 * <p>
 * Focuses on PR conversation (top-level) comments. These are the same {@code IssueComment} entity
 * as issue comments, but GitHub's {@code repository.issues} connection — the issue sync's source —
 * excludes pull requests, so the PR sync is the only path that can observe them. Otherwise a PR
 * conversation comment reaches the mirror only via the non-redeliverable {@code issue_comment}
 * webhook, making a dropped one permanently unrecoverable.
 */
class GitHubPullRequestSyncServiceTest extends BaseUnitTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubPullRequestProcessor pullRequestProcessor;

    @Mock
    private GitHubPullRequestReviewSyncService reviewSyncService;

    @Mock
    private GitHubPullRequestReviewCommentSyncService reviewCommentSyncService;

    @Mock
    private GitHubIssueCommentProcessor issueCommentProcessor;

    @Mock
    private GitHubIssueCommentSyncService issueCommentSyncService;

    @Mock
    private GitHubProjectItemSyncService projectItemSyncService;

    @Mock
    private BackfillStateProvider backfillStateProvider;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private GitHubSyncProperties syncProperties;

    @Mock
    private GitHubExceptionClassifier exceptionClassifier;

    @Mock
    private GitHubGraphQlSyncCoordinator graphQlSyncHelper;

    @Mock
    private HttpGraphQlClient graphQlClient;

    @Mock
    private RequestSpec requestSpec;

    private GitHubPullRequestSyncService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long REPO_ID = 7L;
    private static final Long PR_ID = 500L;
    private static final int PR_NUMBER = 42;
    private static final String NAME_WITH_OWNER = "test-org/test-repo";

    @BeforeEach
    void setUp() {
        lenient()
                .when(exceptionClassifier.classifyWithDetails(any()))
                .thenReturn(ClassificationResult.of(Category.UNKNOWN, "test error"));

        // Full sync (not incremental) keeps the cheap latest-update probe out of the path.
        lenient().when(syncProperties.incrementalSyncEnabled()).thenReturn(false);
        lenient().when(syncProperties.extendedGraphqlTimeout()).thenReturn(Duration.ofSeconds(30));
        lenient().when(syncProperties.paginationThrottle()).thenReturn(Duration.ZERO);

        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
        lenient().when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(false);

        // Run transaction callbacks inline so page processing actually executes.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });

        service = new GitHubPullRequestSyncService(
                repositoryRepository,
                pullRequestRepository,
                graphQlClientProvider,
                pullRequestProcessor,
                reviewSyncService,
                reviewCommentSyncService,
                issueCommentProcessor,
                issueCommentSyncService,
                projectItemSyncService,
                backfillStateProvider,
                transactionTemplate,
                syncProperties,
                new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 3 * * *",
                        15,
                        null,
                        null,
                        null,
                        new SyncSchedulerProperties.ProjectsProperties(true)),
                exceptionClassifier,
                graphQlSyncHelper);
    }

    private Repository createRepository() {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner(NAME_WITH_OWNER);
        return repository;
    }

    private PullRequest createPullRequest() {
        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setNumber(PR_NUMBER);
        return pr;
    }

    private GHIssueComment createGHComment(long databaseId, String body) {
        GHIssueComment comment = new GHIssueComment();
        comment.setId("IC_node" + databaseId);
        comment.setFullDatabaseId(BigInteger.valueOf(databaseId));
        comment.setBody(body);
        comment.setCreatedAt(OffsetDateTime.now());
        comment.setUpdatedAt(OffsetDateTime.now());
        return comment;
    }

    private GHIssueCommentConnection createCommentConnection(
            List<GHIssueComment> comments, int totalCount, boolean hasNextPage, @Nullable String endCursor) {
        GHIssueCommentConnection connection = new GHIssueCommentConnection();
        connection.setNodes(comments);
        connection.setTotalCount(totalCount);
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(hasNextPage);
        if (endCursor != null) pageInfo.setEndCursor(endCursor);
        connection.setPageInfo(pageInfo);
        return connection;
    }

    private GHPullRequest createGHPullRequest(GHIssueCommentConnection comments) {
        GHPullRequest pr = new GHPullRequest();
        pr.setId("PR_node1");
        pr.setFullDatabaseId(BigInteger.valueOf(999L));
        pr.setNumber(PR_NUMBER);
        pr.setTitle("Test PR");
        pr.setUpdatedAt(OffsetDateTime.now());
        pr.setComments(comments);
        return pr;
    }

    private void stubSinglePrPage(GHPullRequest prNode) {
        GHPullRequestConnection connection = new GHPullRequestConnection();
        connection.setNodes(List.of(prNode));
        connection.setTotalCount(1);
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(false);

        connection.setPageInfo(pageInfo);

        when(graphQlClient.documentName("GetRepositoryPullRequests")).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field("repository.pullRequests")).thenReturn(field);
        when(field.toEntity(GHPullRequestConnection.class)).thenReturn(connection);
        when(requestSpec.execute()).thenReturn(Mono.just(response));

        when(repositoryRepository.findById(REPO_ID)).thenReturn(Optional.of(createRepository()));
    }

    @Nested
    class SinglePullRequestRefresh {

        @Test
        void shouldReadOnePullRequestAgainAndProcessItWithTheSyncContext() {
            GHPullRequest prNode = createGHPullRequest(createCommentConnection(List.of(), 0, false, null));
            when(graphQlClient.documentName("GetPullRequestByNumber")).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
            when(response.isValid()).thenReturn(true);
            ClientResponseField field = mock(ClientResponseField.class);
            when(response.field("repository.pullRequest")).thenReturn(field);
            when(field.toEntity(GHPullRequest.class)).thenReturn(prNode);
            when(requestSpec.execute()).thenReturn(Mono.just(response));
            Repository repository = createRepository();
            when(repositoryRepository.findById(REPO_ID)).thenReturn(Optional.of(repository));
            when(pullRequestProcessor.process(any(), any())).thenReturn(createPullRequest());

            boolean refreshed = service.refreshPullRequest(SCOPE_ID, repository, PR_NUMBER);

            assertThat(refreshed).isTrue();
            verify(requestSpec).variable("number", PR_NUMBER);
            verify(graphQlClientProvider).trackRateLimit(SCOPE_ID, response);
            ArgumentCaptor<GitHubPullRequestDTO> captor = ArgumentCaptor.forClass(GitHubPullRequestDTO.class);
            verify(pullRequestProcessor).process(captor.capture(), any(ProcessingContext.class));
            assertThat(captor.getValue().number()).isEqualTo(PR_NUMBER);
        }

        @Test
        void shouldLeaveTheWebhookViewStandingWhenTheRefreshFails() {
            when(graphQlClient.documentName("GetPullRequestByNumber")).thenReturn(requestSpec);
            when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
            when(requestSpec.execute()).thenReturn(Mono.error(new IllegalStateException("GitHub is away")));

            boolean refreshed = service.refreshPullRequest(SCOPE_ID, createRepository(), PR_NUMBER);

            assertThat(refreshed).isFalse();
            verify(pullRequestProcessor, org.mockito.Mockito.never()).process(any(), any());
        }
    }

    @Nested
    class ConversationComments {

        @Test
        void shouldPersistPullRequestConversationCommentsViaIssueCommentProcessor() {
            PullRequest entity = createPullRequest();
            when(pullRequestProcessor.process(any(), any())).thenReturn(entity);
            when(issueCommentProcessor.process(any(), eq(PR_NUMBER), any())).thenReturn(new IssueComment());

            GHIssueCommentConnection comments = createCommentConnection(
                    List.of(createGHComment(1001L, "first conversation comment"), createGHComment(1002L, "second")),
                    2,
                    false,
                    "cursor-end");
            stubSinglePrPage(createGHPullRequest(comments));

            service.syncForRepository(SCOPE_ID, REPO_ID);

            // Routed through the same processor (and therefore the same issue_comment table) that issue
            // comments use, keyed by the PR's number.
            ArgumentCaptor<GitHubCommentDTO> captor = ArgumentCaptor.forClass(GitHubCommentDTO.class);
            verify(issueCommentProcessor, org.mockito.Mockito.times(2))
                    .process(captor.capture(), eq(PR_NUMBER), any(ProcessingContext.class));
            assertThat(captor.getAllValues()).extracting(GitHubCommentDTO::id).containsExactly(1001L, 1002L);
            assertThat(captor.getAllValues())
                    .extracting(GitHubCommentDTO::body)
                    .containsExactly("first conversation comment", "second");
        }

        @Test
        void shouldFetchRemainingCommentsWhenPullRequestHasMoreThanEmbeddedPage() {
            PullRequest entity = createPullRequest();
            when(pullRequestProcessor.process(any(), any())).thenReturn(entity);
            when(issueCommentProcessor.process(any(), eq(PR_NUMBER), any())).thenReturn(new IssueComment());
            when(pullRequestRepository.findByIdWithRepository(PR_ID)).thenReturn(Optional.of(entity));

            GHIssueCommentConnection comments = createCommentConnection(
                    List.of(createGHComment(1001L, "embedded")),
                    25, // more than the embedded page of 10
                    true,
                    "cursor-page-1");
            stubSinglePrPage(createGHPullRequest(comments));

            service.syncForRepository(SCOPE_ID, REPO_ID);

            // The tail is drained from the embedded page's cursor, reusing the issue-comment sync service
            // rather than a parallel PR-only implementation.
            verify(issueCommentSyncService).syncRemainingComments(SCOPE_ID, entity, "cursor-page-1");
        }

        @Test
        void shouldNotPaginateWhenEmbeddedCommentsAreComplete() {
            PullRequest entity = createPullRequest();
            when(pullRequestProcessor.process(any(), any())).thenReturn(entity);
            when(issueCommentProcessor.process(any(), eq(PR_NUMBER), any())).thenReturn(new IssueComment());

            GHIssueCommentConnection comments =
                    createCommentConnection(List.of(createGHComment(1001L, "only comment")), 1, false, "cursor-end");
            stubSinglePrPage(createGHPullRequest(comments));

            service.syncForRepository(SCOPE_ID, REPO_ID);

            verify(issueCommentSyncService, never()).syncRemainingComments(any(), any(), any());
        }

        @Test
        void shouldSyncPullRequestWhenItHasNoConversationComments() {
            // A PR with no comments must not break the sync.
            PullRequest entity = createPullRequest();
            when(pullRequestProcessor.process(any(), any())).thenReturn(entity);
            stubSinglePrPage(createGHPullRequest(createCommentConnection(List.of(), 0, false, null)));

            service.syncForRepository(SCOPE_ID, REPO_ID);

            verify(issueCommentProcessor, never()).process(any(), org.mockito.ArgumentMatchers.anyInt(), any());
            verify(issueCommentSyncService, never()).syncRemainingComments(any(), any(), any());
        }
    }
}
