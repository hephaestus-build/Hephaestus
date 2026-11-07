package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestResponses;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestReviewConnection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Mono;

class GitHubPullRequestReviewSyncServiceTest extends BaseUnitTest {
    private static final long SCOPE = 100L;

    private final GitHubGraphQlClientProvider clients = mock(GitHubGraphQlClientProvider.class);
    private final HttpGraphQlClient client = mock(HttpGraphQlClient.class);
    private final GitHubSyncProperties properties = mock(GitHubSyncProperties.class);

    /** One review on the page has more comments than its first page, and the read of the rest fails. */
    @Test
    void shouldNotReportTheReviewsReadWholeWhenAReviewsCommentsWereNotAllRead() {
        lenient().when(clients.forScope(SCOPE)).thenReturn(client);
        lenient().when(clients.getRateLimitRemaining(SCOPE)).thenReturn(5000);
        lenient().when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(5));
        stub("GetPullRequestReviews", "repository.pullRequest.reviews", GHPullRequestReviewConnection.class, page());
        var comments = mock(RequestSpec.class);
        when(client.documentName("GetReviewComments")).thenReturn(comments);
        when(comments.variable(anyString(), any())).thenReturn(comments);
        var invalid = mock(ClientGraphQlResponse.class);
        when(invalid.isValid()).thenReturn(false);
        when(comments.execute()).thenReturn(Mono.just(invalid));
        var service = new GitHubPullRequestReviewSyncService(
                clients,
                mock(GitHubPullRequestReviewProcessor.class),
                properties,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class),
                mock(PlatformTransactionManager.class));

        SyncResult result = service.syncRemainingReviews(SCOPE, pullRequest(), "after-first-page", 0);

        assertThat(result.status()).isNotEqualTo(SyncResult.Status.COMPLETED);
    }

    @Test
    void shouldStopReviewPagingWithoutDeletingDataWhenGitHubReportsANativeMissingParent() {
        when(clients.forScope(SCOPE)).thenReturn(client);
        lenient().when(clients.getRateLimitRemaining(SCOPE)).thenReturn(5000);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(5));
        var spec = mock(RequestSpec.class);
        when(client.documentName("GetPullRequestReviews")).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var response = GitHubGraphQlTestResponses.decode("""
                {"data":{"repository":{"pullRequest":null}},"errors":[{"type":"NOT_FOUND","path":["repository","pullRequest"],"message":"Synthetic missing pull request"}]}
                """);
        when(spec.execute()).thenReturn(Mono.just(response));
        var processor = mock(GitHubPullRequestReviewProcessor.class);
        var service = new GitHubPullRequestReviewSyncService(
                clients,
                processor,
                properties,
                mock(GitHubExceptionClassifier.class),
                mock(GitHubGraphQlSyncCoordinator.class),
                mock(PlatformTransactionManager.class));
        var result = service.syncRemainingReviews(SCOPE, pullRequest(), "after-first-page", 0);
        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        assertThat(result.count()).isZero();
        verifyNoInteractions(processor);
    }

    private <T> void stub(String document, String path, Class<T> type, T entity) {
        var spec = mock(RequestSpec.class);
        when(client.documentName(document)).thenReturn(spec);
        when(spec.variable(anyString(), any())).thenReturn(spec);
        var response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        var field = mock(ClientResponseField.class);
        when(response.field(path)).thenReturn(field);
        when(field.toEntity(type)).thenReturn(entity);
        when(spec.execute()).thenReturn(Mono.just(response));
    }

    private static GHPullRequestReviewConnection page() {
        var more = new GHPageInfo();
        more.setHasNextPage(true);
        more.setEndCursor("after-first-comment");
        var comments = new GHPullRequestReviewCommentConnection();
        comments.setNodes(List.of());
        comments.setTotalCount(2);
        comments.setPageInfo(more);
        var review = new GHPullRequestReview();
        review.setId("R_1");
        review.setComments(comments);
        var end = new GHPageInfo();
        end.setHasNextPage(false);
        var connection = new GHPullRequestReviewConnection();
        connection.setNodes(List.of(review));
        connection.setTotalCount(1);
        connection.setPageInfo(end);
        return connection;
    }

    private static PullRequest pullRequest() {
        var repository = new Repository();
        repository.setNameWithOwner("owner/repo");
        var pr = new PullRequest();
        pr.setId(500L);
        pr.setNumber(42);
        pr.setRepository(repository);
        return pr;
    }
}
