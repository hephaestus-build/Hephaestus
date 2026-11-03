package de.tum.cit.aet.hephaestus.integration.scm.github.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetTestBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
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
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.BackfillPageObserver;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.backfill.GitHubHistoricalBackfillService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigInteger;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real sync and the real capture, with only the provider stubbed: what the mirror holds and what capture says about
 * it when a list was not read to its end.
 */
class PartialDiscussionMirrorIntegrationTest extends BaseIntegrationTest {
    private static final int NUMBER = 42;
    private static final OffsetDateTime AT = OffsetDateTime.of(2025, 1, 3, 10, 0, 0, 0, ZoneOffset.UTC);

    @MockitoBean
    private GitHubGraphQlClientProvider clients;

    @Autowired
    private GitHubPullRequestSyncService sync;

    @Autowired
    private GitHubHistoricalBackfillService backfill;

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

    private final HttpGraphQlClient client = mock(HttpGraphQlClient.class);
    private final AtomicBoolean critical = new AtomicBoolean();
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
        stubPage("GetRepositoryPullRequestsHistorical", pullRequest());
        var noIssues = new GHIssueConnection();
        noIssues.setNodes(List.of());
        noIssues.setTotalCount(0);
        noIssues.setPageInfo(page(false, null));
        var issueSpec = mock(RequestSpec.class);
        when(client.documentName("GetRepositoryIssuesHistorical")).thenReturn(issueSpec);
        when(issueSpec.variable(anyString(), any())).thenReturn(issueSpec);
        var issues = valid("repository.issues", GHIssueConnection.class, noIssues);
        when(issueSpec.execute()).thenReturn(Mono.just(issues));
        var pagerSpec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestReviewComments")).thenReturn(pagerSpec);
        when(pagerSpec.variable(anyString(), any())).thenReturn(pagerSpec);
        var sixth = new GHPullRequestReviewThreadConnection();
        sixth.setNodes(List.of(thread(6, false)));
        sixth.setTotalCount(6);
        sixth.setPageInfo(page(false, null));
        var pager = valid("repository.pullRequest.reviewThreads", GHPullRequestReviewThreadConnection.class, sixth);
        when(pagerSpec.execute()).thenReturn(Mono.just(pager));

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

    private static GHPullRequestReviewThread thread(int index, boolean resolved) {
        var thread = new GHPullRequestReviewThread();
        thread.setId("T_" + index);
        thread.setPath("src/file" + index + ".ts");
        thread.setLine(1);
        thread.setIsResolved(resolved);
        thread.setIsOutdated(false);
        thread.setIsCollapsed(false);
        var comment = new GHPullRequestReviewComment();
        comment.setId("C_" + index);
        comment.setFullDatabaseId(BigInteger.valueOf(base + index));
        comment.setBody("Please change this " + index);
        comment.setPath("src/file" + index + ".ts");
        comment.setDiffHunk("@@ -1 +1 @@");
        comment.setCreatedAt(AT);
        comment.setUpdatedAt(AT);
        var comments = new GHPullRequestReviewCommentConnection();
        comments.setNodes(List.of(comment));
        comments.setTotalCount(1);
        comments.setPageInfo(page(false, null));
        thread.setComments(comments);
        return thread;
    }
}
