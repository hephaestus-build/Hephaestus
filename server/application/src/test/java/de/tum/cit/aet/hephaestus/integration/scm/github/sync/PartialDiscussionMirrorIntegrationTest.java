package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewThreadConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestState;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.BackfillPageObserver;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubBackfillRepair;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigInteger;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real sync and the real capture, with only the provider stubbed: what the mirror holds and what capture says about
 * it when a list was not read to its end.
 */
class PartialDiscussionMirrorIntegrationTest extends BaseIntegrationTest {
    private static final int NUMBER = 42;
    private static final OffsetDateTime AT = OffsetDateTime.of(2025, 1, 3, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ReviewThreadContentSource threadSource;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private PullRequestReviewThreadRepository threads;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdentityProviderRepository providers;

    // A plain mock, not a @MockitoBean: the services below are built over it, so the shared context stays unchanged.
    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);
    private final HttpGraphQlClient client = mock(HttpGraphQlClient.class);
    private GitHubPullRequestSyncService sync;
    private GitHubPullRequestReviewCommentSyncService reviewComments;
    private GitHubHistoricalBackfillService backfill;
    private final AtomicBoolean critical = new AtomicBoolean();
    // Whether a transaction was open each time GitHub was asked for the rest of a list.
    private final List<Boolean> askedInTransaction = new ArrayList<>();
    private long workspaceId;
    private long repositoryId;
    private long monitorId;
    private static long base;
    private String repositoryName = "";

    @BeforeEach
    void repositoryAndProvider() {
        String unique = UUID.randomUUID().toString();
        long installation = (unique.hashCode() & 0x7fffffffL) + 1L;
        var workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installation, "partial-" + unique),
                installation);
        workspaceId = Objects.requireNonNull(workspace.getId());
        // The workspace's own connection names this provider, and the backfill finds repositories through it.
        long providerId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() ->
                        providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")))
                .getId());
        // Native ids of this test alone: other tests share the github.com provider.
        base = 1_000_000L + (unique.hashCode() & 0xfffffL) * 1_000L;
        String name = "partial/" + unique;
        repositoryName = name;
        repositoryId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,html_url,pushed_at,visibility)
                VALUES (?,?,'repo',?,false,false,false,'https://example.com/repo',now(),'PUBLIC') RETURNING id
                """, Long.class, base, providerId, name));
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, name);
        monitor.setNativeId(base);
        monitorId = Objects.requireNonNull(monitors.saveAndFlush(monitor).getId());

        when(clients.forScope(workspaceId)).thenReturn(client);
        lenient().when(clients.getRateLimitRemaining(workspaceId)).thenReturn(5000);
        lenient().when(clients.isRateLimitCritical(workspaceId)).thenAnswer(invocation -> critical.get());
        lenient().when(client.documentName(anyString())).thenAnswer(invocation -> mock(RequestSpec.class));
        wireServicesOverTheStubbedProvider();
    }

    private void wireServicesOverTheStubbedProvider() {
        var properties = context.getBean(GitHubSyncProperties.class);
        var classifier = context.getBean(GitHubExceptionClassifier.class);
        var coordinator = context.getBean(GitHubGraphQlSyncCoordinator.class);
        var repositories = context.getBean(RepositoryRepository.class);
        var reviews = transactional(
                GitHubPullRequestReviewSyncService.class,
                new GitHubPullRequestReviewSyncService(
                        clients,
                        context.getBean(GitHubPullRequestReviewProcessor.class),
                        properties,
                        classifier,
                        coordinator,
                        transactionManager));
        reviewComments = transactional(
                GitHubPullRequestReviewCommentSyncService.class,
                new GitHubPullRequestReviewCommentSyncService(
                        threads,
                        clients,
                        context.getBean(GitHubPullRequestReviewCommentProcessor.class),
                        context.getBean(GitHubUserProcessor.class),
                        properties,
                        classifier,
                        coordinator,
                        new TransactionTemplate(transactionManager)));
        var issueComments = transactional(
                GitHubIssueCommentSyncService.class,
                new GitHubIssueCommentSyncService(
                        clients,
                        context.getBean(GitHubIssueCommentProcessor.class),
                        properties,
                        classifier,
                        coordinator,
                        repositories,
                        new TransactionTemplate(transactionManager)));
        var transactions = new TransactionTemplate(transactionManager);
        sync = new GitHubPullRequestSyncService(
                repositories,
                pullRequests,
                clients,
                context.getBean(GitHubPullRequestProcessor.class),
                reviews,
                reviewComments,
                context.getBean(GitHubIssueCommentProcessor.class),
                issueComments,
                context.getBean(BackfillStateProvider.class),
                transactions,
                properties,
                context.getBean(SyncSchedulerProperties.class),
                classifier,
                coordinator);
        backfill = new GitHubHistoricalBackfillService(
                context.getBean(SyncTargetProvider.class),
                context.getBean(BackfillStateProvider.class),
                context.getBean(SyncSchedulerProperties.class),
                properties,
                clients,
                context.getBean(GitHubIssueProcessor.class),
                context.getBean(GitHubIssueCommentProcessor.class),
                context.getBean(GitHubPullRequestProcessor.class),
                context.getBean(GitHubPullRequestReviewProcessor.class),
                reviews,
                reviewComments,
                issueComments,
                pullRequests,
                repositories,
                transactions,
                context.getBean("monitoringExecutor", Executor.class),
                context.getBean(ActivityLedgerRepair.class),
                context.getBean(GitHubBackfillRepair.class));
    }

    /** Applies the service's own {@code @Transactional} methods, as its Spring proxy does. */
    private <T> T transactional(Class<T> type, T service) {
        TransactionManager manager = transactionManager;
        var factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }

    /** A provider with six threads: the first page holds five resolved ones. The sixth, still open, never arrives. */
    @Test
    void shouldReportTheStopAndAPartialCaptureWhenARateLimitEndsTheThreadPager() {
        stubPullRequestPage();
        stubThreadPagerHittingACriticalRateLimit();

        SyncResult result = sync.syncForRepository(workspaceId, repositoryId, null, null, null);

        long pullRequestId = pullRequests
                .findByRepositoryIdAndNumber(repositoryId, NUMBER)
                .orElseThrow()
                .getId();
        assertThat(threads.findRecentIdsByPullRequestId(pullRequestId, Pageable.unpaged()))
                .hasSize(5);
        assertThat(result.status())
                .as("a sync that a rate limit cut short says so, and its timestamp stays for a retry")
                .isEqualTo(SyncResult.Status.ABORTED_RATE_LIMIT);

        assertThat(threadCapture(pullRequestId).completeness().get(ReviewThreadContentSource.KIND))
                .as("five of six threads are not the whole record")
                .isEqualTo(SourceCompleteness.PARTIAL);
    }

    /** A backfill reads the lists past their first page too, instead of leaving them to a sync that may never come. */
    @Test
    void shouldReadEveryThreadAndCaptureThemWholeWhenABackfilledListHasMorePages() {
        stubPage("GetRepositoryPullRequestsHistorical", pullRequestWithAThreadCommentPage());
        stubTheRestOfTheFirstThread();
        var noIssues = new GHIssueConnection();
        noIssues.setNodes(List.of());
        noIssues.setTotalCount(0);
        noIssues.setPageInfo(page(false, null));
        var issueSpec = mock(RequestSpec.class);
        when(client.documentName("GetRepositoryIssuesHistorical")).thenReturn(issueSpec);
        when(issueSpec.variable(anyString(), any())).thenReturn(issueSpec);
        var issues = valid("repository.issues", GHIssueConnection.class, noIssues);
        when(issueSpec.execute()).thenReturn(Mono.just(issues));
        stubTheSixthThread();

        backfill.runBackfillBatch(
                SyncTargetTestBuilder.syncTarget()
                        .id(monitorId)
                        .scopeId(workspaceId)
                        .repositoryNameWithOwner(repositoryName)
                        .issueBackfillHighWaterMark(0)
                        .issueBackfillCheckpoint(0)
                        .build(),
                10,
                BackfillPageObserver.NOOP);

        long pullRequestId = pullRequests
                .findByRepositoryIdAndNumber(repositoryId, NUMBER)
                .orElseThrow()
                .getId();
        assertThat(threads.findRecentIdsByPullRequestId(pullRequestId, Pageable.unpaged()))
                .hasSize(6);
        assertThat(threadCapture(pullRequestId).completeness().get(ReviewThreadContentSource.KIND))
                .isEqualTo(SourceCompleteness.COMPLETE);
        assertTheRestWasAskedForOutsideTransactionsAndStored();
    }

    /** A sync reads the rest of a thread list and of a thread's comments with no transaction open, then stores both. */
    @Test
    void shouldAskGitHubWithNoTransactionOpenWhenAThreadListAndItsCommentsHaveMorePages() {
        stubPage("GetRepositoryPullRequests", pullRequestWithAThreadCommentPage());
        stubTheRestOfTheFirstThread();
        stubTheSixthThread();

        sync.syncForRepository(workspaceId, repositoryId, null, null, null);

        long pullRequestId = pullRequests
                .findByRepositoryIdAndNumber(repositoryId, NUMBER)
                .orElseThrow()
                .getId();
        assertThat(threads.findRecentIdsByPullRequestId(pullRequestId, Pageable.unpaged()))
                .hasSize(6);
        assertTheRestWasAskedForOutsideTransactionsAndStored();
    }

    /** A thread page that fails after another was read leaves the threads already read stored, and says it stopped. */
    @Test
    void shouldStoreTheThreadsAlreadyReadWhenALaterThreadPageFails() {
        stubPullRequestPage();
        var sixth = new GHPullRequestReviewThreadConnection();
        sixth.setNodes(List.of(thread(6, false)));
        sixth.setTotalCount(7);
        sixth.setPageInfo(page(true, "after-six"));
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestReviewComments")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var pager = valid("repository.pullRequest.reviewThreads", GHPullRequestReviewThreadConnection.class, sixth);
        when(spec.execute())
                .thenReturn(Mono.just(pager))
                .thenReturn(Mono.error(new IllegalStateException("GitHub answered nothing usable")));

        sync.syncForRepository(workspaceId, repositoryId, null, null, null);

        long pullRequestId = pullRequests
                .findByRepositoryIdAndNumber(repositoryId, NUMBER)
                .orElseThrow()
                .getId();
        assertThat(threads.findRecentIdsByPullRequestId(pullRequestId, Pageable.unpaged()))
                .as("the five embedded threads and the sixth from the page that arrived")
                .hasSize(6);
        assertThat(threadCapture(pullRequestId).completeness().get(ReviewThreadContentSource.KIND))
                .isEqualTo(SourceCompleteness.PARTIAL);
    }

    /** A database error in the write reaches the caller, whose retry decides what a deadlock or a lost row means. */
    @Test
    void shouldReachTheCallerWhenStoringTheRemainingThreadsFails() {
        stubTheSixthThread();
        // A pull request deleted while GitHub answered: its threads cannot be stored.
        var deleted = new PullRequest();
        deleted.setId(Long.MAX_VALUE);
        deleted.setNumber(NUMBER);
        deleted.setRepository(context.getBean(RepositoryRepository.class)
                .findByIdWithOrganization(repositoryId)
                .orElseThrow());

        assertThatThrownBy(() -> reviewComments.syncRemainingThreads(workspaceId, deleted, "after-five"))
                .isInstanceOf(DataAccessException.class);
    }

    /** A sync reads the rest of a pull request's conversation with no transaction open, then stores it. */
    @Test
    void shouldAskGitHubWithNoTransactionOpenWhenAConversationHasMorePages() {
        stubPage("GetRepositoryPullRequests", pullRequestWithAConversationPage());
        stubTheSixthThread();
        stubTheConversation(Mono.just(conversationPage(base + 200, false)));

        sync.syncForRepository(workspaceId, repositoryId, null, null, null);

        assertThat(askedInTransaction)
                .as("the second thread page, then the second conversation page, each with no transaction open")
                .containsExactly(false, false);
        assertThat(issueComments(base + 200)).isEqualTo(1);
    }

    /** A conversation page that fails after another was read leaves the comments already read stored. */
    @Test
    void shouldStoreTheCommentsAlreadyReadWhenALaterConversationPageFails() {
        stubPage("GetRepositoryPullRequests", pullRequestWithAConversationPage());
        stubTheSixthThread();
        stubTheConversation(
                Mono.just(conversationPage(base + 300, true)),
                Mono.error(new IllegalStateException("GitHub answered nothing usable")));

        sync.syncForRepository(workspaceId, repositoryId, null, null, null);

        assertThat(issueComments(base + 300)).isEqualTo(1);
    }

    private long issueComments(long nativeId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT count(*) FROM issue_comment WHERE native_id = ?", Long.class, nativeId));
    }

    /** The conversation continues past the embedded page; each request answers with the next of the given pages. */
    private void stubTheConversation(Mono<ClientGraphQlResponse> first, Mono<ClientGraphQlResponse> rest) {
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestComments")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var answers = new ArrayDeque<>(List.of(first, rest));
        when(spec.execute()).thenAnswer(invocation -> {
            askedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return answers.size() > 1 ? answers.poll() : answers.peek();
        });
    }

    private void stubTheConversation(Mono<ClientGraphQlResponse> only) {
        stubTheConversation(only, only);
    }

    private ClientGraphQlResponse conversationPage(long nativeId, boolean more) {
        var comment = new GHIssueComment();
        comment.setId("IC_" + nativeId);
        comment.setFullDatabaseId(BigInteger.valueOf(nativeId));
        comment.setBody("A conversation comment");
        comment.setUrl(URI.create("https://example.com/repo/pull/42#issuecomment-" + nativeId));
        comment.setCreatedAt(AT);
        comment.setUpdatedAt(AT);
        var connection = new GHIssueCommentConnection();
        connection.setNodes(List.of(comment));
        connection.setTotalCount(3);
        connection.setPageInfo(page(more, "after-" + nativeId));
        return valid("repository.pullRequest.comments", GHIssueCommentConnection.class, connection);
    }

    private static GHPullRequest pullRequestWithAConversationPage() {
        var pr = pullRequest();
        pr.getComments().setTotalCount(3);
        pr.getComments().setPageInfo(page(true, "after-embedded"));
        return pr;
    }

    private void assertTheRestWasAskedForOutsideTransactionsAndStored() {
        assertThat(askedInTransaction)
                .as(
                        "the first thread's second comment page, then the second thread page, each with no transaction open")
                .containsExactly(false, false);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pull_request_review_comment WHERE native_id = ?", Long.class, base + 100))
                .as("the comment from the second page is stored")
                .isEqualTo(1L);
    }

    /** The first thread's comments continue on a second page, which holds one reply. */
    private void stubTheRestOfTheFirstThread() {
        var reply = comment("C_1_reply", base + 100, "src/file1.ts");
        reply.setReplyTo(comment("C_1", base + 1, "src/file1.ts"));
        var rest = new GHPullRequestReviewCommentConnection();
        rest.setNodes(List.of(reply));
        rest.setTotalCount(2);
        rest.setPageInfo(page(false, null));
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetThreadComments")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        answerRecordingTheTransaction(spec, valid("node.comments", GHPullRequestReviewCommentConnection.class, rest));
    }

    /** The thread list continues on a second page, which holds the sixth thread. */
    private void stubTheSixthThread() {
        var sixth = new GHPullRequestReviewThreadConnection();
        sixth.setNodes(List.of(thread(6, false)));
        sixth.setTotalCount(6);
        sixth.setPageInfo(page(false, null));
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestReviewComments")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        answerRecordingTheTransaction(
                spec, valid("repository.pullRequest.reviewThreads", GHPullRequestReviewThreadConnection.class, sixth));
    }

    private void answerRecordingTheTransaction(RequestSpec spec, ClientGraphQlResponse response) {
        when(spec.execute()).thenAnswer(invocation -> {
            askedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Mono.just(response);
        });
    }

    private void stubPullRequestPage() {
        stubPage("GetRepositoryPullRequests", pullRequest());
    }

    private void stubPage(String document, GHPullRequest pullRequest) {
        var connection = new GHPullRequestConnection();
        connection.setNodes(List.of(pullRequest));
        connection.setTotalCount(1);
        connection.setPageInfo(page(false, null));
        var spec = mock(RequestSpec.class);
        when(client.documentName(document)).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var response = valid("repository.pullRequests", GHPullRequestConnection.class, connection);
        when(spec.execute()).thenReturn(Mono.just(response));
    }

    private CaptureContribution threadCapture(long pullRequestId) {
        var metadata = JsonMapper.builder().build().createObjectNode();
        metadata.put("repository_id", repositoryId);
        metadata.put("pull_request_id", pullRequestId);
        return threadSource.capture(
                ScmCaptureFixtures.context(metadata, workspaceId, false), Set.of(ReviewThreadContentSource.KIND));
    }

    private void stubThreadPagerHittingACriticalRateLimit() {
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestReviewComments")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var pager = mock(ClientGraphQlResponse.class);
        lenient().when(pager.isValid()).thenReturn(true);
        when(spec.execute()).thenReturn(Mono.just(pager));
        doAnswer(invocation -> {
                    critical.set(true);
                    return null;
                })
                .when(clients)
                .trackRateLimit(eq(workspaceId), eq(pager));
    }

    private static <T> ClientGraphQlResponse valid(String path, Class<T> type, T entity) {
        var response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        lenient().when(response.getErrors()).thenReturn(List.of());
        var field = mock(ClientResponseField.class);
        when(response.field(path)).thenReturn(field);
        when(field.toEntity(type)).thenReturn(entity);
        return response;
    }

    private static GHPageInfo page(boolean more, @Nullable String cursor) {
        var info = new GHPageInfo();
        info.setHasNextPage(more);
        if (cursor != null) info.setEndCursor(cursor);
        return info;
    }

    private static GHPullRequest pullRequest() {
        var pr = new GHPullRequest();
        pr.setId("PR_node");
        pr.setFullDatabaseId(BigInteger.valueOf(base));
        pr.setNumber(NUMBER);
        pr.setTitle("Work");
        pr.setState(GHPullRequestState.OPEN);
        pr.setCreatedAt(AT);
        pr.setUpdatedAt(AT);
        pr.setBaseRefName("main");
        pr.setHeadRefName("feature");
        pr.setBaseRefOid("b".repeat(40));
        pr.setHeadRefOid("a".repeat(40));
        pr.setUrl(URI.create("https://example.com/repo/pull/42"));
        var comments = new GHIssueCommentConnection();
        comments.setNodes(List.of());
        comments.setTotalCount(0);
        comments.setPageInfo(page(false, null));
        pr.setComments(comments);
        var reviews = new GHPullRequestReviewConnection();
        reviews.setNodes(List.of());
        reviews.setTotalCount(0);
        reviews.setPageInfo(page(false, null));
        pr.setReviews(reviews);
        var threadConnection = new GHPullRequestReviewThreadConnection();
        threadConnection.setNodes(new ArrayList<>(IntStream.rangeClosed(1, 5)
                .mapToObj(index -> thread(index, true))
                .toList()));
        threadConnection.setTotalCount(6);
        threadConnection.setPageInfo(page(true, "after-five"));
        pr.setReviewThreads(threadConnection);
        return pr;
    }

    private static GHPullRequest pullRequestWithAThreadCommentPage() {
        var pr = pullRequest();
        var first = Objects.requireNonNull(pr.getReviewThreads().getNodes())
                .getFirst()
                .getComments();
        first.setTotalCount(2);
        first.setPageInfo(page(true, "after-first-comment"));
        return pr;
    }

    private static GHPullRequestReviewThread thread(int index, boolean resolved) {
        var thread = new GHPullRequestReviewThread();
        thread.setId("T_" + index);
        thread.setPath("src/file" + index + ".ts");
        thread.setLine(1);
        thread.setIsResolved(resolved);
        thread.setIsOutdated(false);
        thread.setIsCollapsed(false);
        var comments = new GHPullRequestReviewCommentConnection();
        comments.setNodes(List.of(comment("C_" + index, base + index, "src/file" + index + ".ts")));
        comments.setTotalCount(1);
        comments.setPageInfo(page(false, null));
        thread.setComments(comments);
        return thread;
    }

    private static GHPullRequestReviewComment comment(String id, long databaseId, String path) {
        var comment = new GHPullRequestReviewComment();
        comment.setId(id);
        comment.setFullDatabaseId(BigInteger.valueOf(databaseId));
        comment.setBody("Please change this " + id);
        comment.setPath(path);
        comment.setDiffHunk("@@ -1 +1 @@");
        comment.setCreatedAt(AT);
        comment.setUpdatedAt(AT);
        return comment;
    }
}
