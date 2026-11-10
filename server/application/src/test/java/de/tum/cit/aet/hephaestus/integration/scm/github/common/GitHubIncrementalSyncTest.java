package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.BackfillProperties;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties.FilterProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.BackfillStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.GitHubIssueCommentSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubClosingIssueClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview.GitHubPullRequestReviewSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.GitHubPullRequestReviewCommentSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class GitHubIncrementalSyncTest extends BaseUnitTest {
    private static final Instant WATERMARK = Instant.parse("2026-01-01T12:00:00Z");
    private static final GitHubSyncProperties SYNC = new GitHubSyncProperties(
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120),
            Duration.ZERO,
            Duration.ofMinutes(5),
            10);
    private static final SyncSchedulerProperties SCHEDULE = new SyncSchedulerProperties(
            true,
            7,
            "0 0 3 * * *",
            60,
            new BackfillProperties(true, 50, 500, 60),
            new FilterProperties(Set.of(), Set.of()));

    @Mock
    private RepositoryRepository repositories;

    @Mock
    private GitHubGraphQlClientProvider clients;

    @Mock
    private TransactionTemplate transactions;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private RequestSpec request;

    @Mock
    private ClientGraphQlResponse response;

    @Mock
    private ClientResponseField field;

    private final List<String> documents = new ArrayList<>();
    private final Map<String, Object> variables = new HashMap<>();

    @BeforeEach
    void setUp() {
        var repository = new Repository();
        repository.setId(2L);
        repository.setNameWithOwner("test-org/test-repo");
        when(repositories.findById(2L)).thenReturn(Optional.of(repository));
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(clients.forScope(1L)).thenReturn(client);
        when(client.documentName(anyString())).thenAnswer(invocation -> {
            documents.add(invocation.getArgument(0));
            return request;
        });
        when(request.variable(anyString(), any())).thenAnswer(invocation -> {
            variables.put(invocation.getArgument(0), invocation.getArgument(1));
            return request;
        });
        when(request.execute()).thenReturn(Mono.just(response));
        when(response.isValid()).thenReturn(true);
        when(response.field(anyString())).thenReturn(field);
    }

    @Test
    void shouldKeepTheIssueWatermarkOverlapAndSkipEmptyReads() {
        when(field.toEntity(Integer.class)).thenReturn(0);

        assertThat(issues().syncForRepository(1L, 2L, null, null, WATERMARK)).isEqualTo(SyncResult.completed(0));

        assertThat(variables.get("since")).isEqualTo("2026-01-01T11:55Z");
        assertThat(documents).containsExactly("GetRepositoryIssueCount");
    }

    @Test
    void shouldLimitTheFirstIssueReadToTheConfiguredTimeframe() {
        when(field.toEntity(Integer.class)).thenReturn(0);
        Instant earliest = Instant.now().minus(Duration.ofDays(7));

        assertThat(issues().syncForRepository(1L, 2L, null, null, null)).isEqualTo(SyncResult.completed(0));

        assertThat(OffsetDateTime.parse((String) variables.get("since")).toInstant())
                .isBetween(earliest, Instant.now().minus(Duration.ofDays(7)));
        assertThat(documents).containsExactly("GetRepositoryIssueCount");
    }

    @Test
    void shouldResumeIssuePagesWithTheSameWatermarkOverlap() {
        var empty = new GHIssueConnection();
        empty.setNodes(List.of());
        when(field.toEntity(GHIssueConnection.class)).thenReturn(empty);

        issues().syncForRepository(1L, 2L, null, "resume-cursor", WATERMARK);

        assertThat(documents).containsExactly("GetRepositoryIssues");
        assertThat(variables).containsEntry("after", "resume-cursor").containsEntry("since", "2026-01-01T11:55Z");
    }

    @Test
    void shouldReadPullRequestsInsideTheWatermarkOverlap() {
        latestPullRequest("2026-01-01T11:57:00Z");
        emptyPullRequestPage();

        pullRequests().syncForRepository(1L, 2L, null, null, WATERMARK);

        assertThat(documents).containsExactly("GetRepositoryPullRequestLatestUpdate", "GetRepositoryPullRequests");
    }

    @Test
    void shouldSkipPullRequestsBeforeTheBufferedWatermark() {
        latestPullRequest("2026-01-01T11:54:00Z");

        assertThat(pullRequests().syncForRepository(1L, 2L, null, null, WATERMARK))
                .isEqualTo(SyncResult.completed(0));

        assertThat(documents).containsExactly("GetRepositoryPullRequestLatestUpdate");
    }

    @Test
    void shouldReadPullRequestPagesWhenTheProbeHasNoUpdateTime() {
        when(field.toEntityList(ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
                .thenReturn(List.of(Map.of("number", 1)));
        emptyPullRequestPage();

        pullRequests().syncForRepository(1L, 2L, null, null, WATERMARK);

        assertThat(documents).containsExactly("GetRepositoryPullRequestLatestUpdate", "GetRepositoryPullRequests");
    }

    @Test
    void shouldResumePullRequestPagesWithoutRepeatingTheProbe() {
        emptyPullRequestPage();

        pullRequests().syncForRepository(1L, 2L, null, "resume-cursor", WATERMARK);

        assertThat(documents).containsExactly("GetRepositoryPullRequests");
        assertThat(variables).containsEntry("after", "resume-cursor");
    }

    @Test
    void shouldReadRecentPullRequestsOnTheFirstSync() {
        latestPullRequest(Instant.now().minus(Duration.ofDays(6)).toString());
        emptyPullRequestPage();

        pullRequests().syncForRepository(1L, 2L, null, null, null);

        assertThat(documents).containsExactly("GetRepositoryPullRequestLatestUpdate", "GetRepositoryPullRequests");
    }

    @Test
    void shouldLeaveOlderPullRequestsToHistoricalBackfillOnTheFirstSync() {
        latestPullRequest(Instant.now().minus(Duration.ofDays(8)).toString());

        assertThat(pullRequests().syncForRepository(1L, 2L, null, null, null)).isEqualTo(SyncResult.completed(0));

        assertThat(documents).containsExactly("GetRepositoryPullRequestLatestUpdate");
    }

    private void latestPullRequest(String updatedAt) {
        when(field.toEntityList(ArgumentMatchers.<ParameterizedTypeReference<Map<String, Object>>>any()))
                .thenReturn(List.of(Map.of("updatedAt", updatedAt)));
    }

    private void emptyPullRequestPage() {
        var empty = new GHPullRequestConnection();
        empty.setNodes(List.of());
        when(field.toEntity(GHPullRequestConnection.class)).thenReturn(empty);
    }

    private GitHubIssueSyncService issues() {
        return new GitHubIssueSyncService(
                repositories,
                clients,
                mock(GitHubIssueProcessor.class),
                mock(GitHubIssueCommentProcessor.class),
                mock(GitHubIssueCommentSyncService.class),
                mock(BackfillStateProvider.class),
                transactions,
                SYNC,
                SCHEDULE,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class));
    }

    private GitHubPullRequestSyncService pullRequests() {
        return new GitHubPullRequestSyncService(
                repositories,
                mock(PullRequestRepository.class),
                clients,
                mock(GitHubPullRequestProcessor.class),
                mock(GitHubClosingIssueClient.class),
                mock(GitHubPullRequestReviewSyncService.class),
                mock(GitHubPullRequestReviewCommentSyncService.class),
                mock(GitHubIssueCommentProcessor.class),
                mock(GitHubIssueCommentSyncService.class),
                mock(BackfillStateProvider.class),
                transactions,
                SYNC,
                SCHEDULE,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class));
    }
}
