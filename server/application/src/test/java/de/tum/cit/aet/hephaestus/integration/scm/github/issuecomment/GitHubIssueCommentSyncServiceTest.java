package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.Category;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier.ClassificationResult;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient.RequestSpec;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitHubIssueCommentSyncService}.
 * <p>
 * Covers the tail-pagination path shared by issues and pull requests. A PR's conversation comments
 * are the same {@code IssueComment} entity as an issue's, but GitHub exposes them under a different
 * root: {@code repository.issue(number:)} does not resolve pull requests, so a PR must be queried
 * via {@code repository.pullRequest(number:)}. Sending a PR down the issue query would silently
 * return nothing — permanently losing the comments, since the issue_comment webhook is the only
 * other source and is not redeliverable.
 */
class GitHubIssueCommentSyncServiceTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitHubIssueCommentProcessor commentProcessor;

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

    private GitHubIssueCommentSyncService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long REPO_ID = 7L;
    private static final int NUMBER = 42;
    private static final String NAME_WITH_OWNER = "test-org/test-repo";

    @BeforeEach
    void setUp() {
        lenient()
                .when(exceptionClassifier.classifyWithDetails(any()))
                .thenReturn(ClassificationResult.of(Category.UNKNOWN, "test error"));
        lenient().when(syncProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(30));
        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(graphQlClient);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(5000);
        lenient().when(graphQlClientProvider.isRateLimitCritical(SCOPE_ID)).thenReturn(false);

        service = new GitHubIssueCommentSyncService(
                graphQlClientProvider, commentProcessor, syncProperties, exceptionClassifier, graphQlSyncHelper);
    }

    // Helper methods

    private Repository createRepository() {
        Repository repository = new Repository();
        repository.setId(REPO_ID);
        repository.setNameWithOwner(NAME_WITH_OWNER);
        return repository;
    }

    private GHIssueComment createGHComment(long databaseId, String body) {
        GHIssueComment comment = new GHIssueComment();
        comment.setId("IC_node" + databaseId);
        comment.setFullDatabaseId(BigInteger.valueOf(databaseId));
        comment.setBody(body);
        comment.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        comment.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return comment;
    }

    /**
     * Stubs one terminal page of comments returned at {@code fieldPath}.
     */
    private void stubSingleCommentPage(String documentName, String fieldPath, List<GHIssueComment> comments) {
        GHIssueCommentConnection connection = new GHIssueCommentConnection();
        connection.setNodes(comments);
        connection.setTotalCount(comments.size());
        GHPageInfo pageInfo = new GHPageInfo();
        pageInfo.setHasNextPage(false);

        connection.setPageInfo(pageInfo);

        when(graphQlClient.documentName(documentName)).thenReturn(requestSpec);
        when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.isValid()).thenReturn(true);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(fieldPath)).thenReturn(field);
        when(field.toEntity(GHIssueCommentConnection.class)).thenReturn(connection);
        when(requestSpec.execute()).thenReturn(Mono.just(response));
    }

    @Nested
    class SyncRemainingComments {

        @Test
        void shouldQueryPullRequestRootWhenParentIsPullRequest() {
            PullRequest pr = new PullRequest();
            pr.setId(500L);
            pr.setNumber(NUMBER);
            pr.setRepository(createRepository());
            when(commentProcessor.process(any(), eq(NUMBER), any())).thenReturn(new IssueComment());
            stubSingleCommentPage(
                    "GetPullRequestComments",
                    "repository.pullRequest.comments",
                    List.of(createGHComment(1001L, "tail comment")));

            int synced = service.syncRemainingComments(SCOPE_ID, pr, "cursor-page-1");

            // PR root, resuming from the embedded page's cursor.
            assertThat(synced).isEqualTo(1);
            verify(graphQlClient).documentName("GetPullRequestComments");
            verify(requestSpec).variable("number", NUMBER);
            verify(requestSpec).variable("after", "cursor-page-1");

            ArgumentCaptor<GitHubCommentDTO> captor = ArgumentCaptor.forClass(GitHubCommentDTO.class);
            verify(commentProcessor).process(captor.capture(), eq(NUMBER), any());
            assertThat(captor.getValue().id()).isEqualTo(1001L);
        }

        @Test
        void shouldQueryIssueRootWhenParentIsIssue() {
            Issue issue = new Issue();
            issue.setId(500L);
            issue.setNumber(NUMBER);
            issue.setRepository(createRepository());
            when(commentProcessor.process(any(), eq(NUMBER), any())).thenReturn(new IssueComment());
            stubSingleCommentPage(
                    "GetIssueComments",
                    "repository.issue.comments",
                    List.of(createGHComment(2001L, "issue tail comment")));

            int synced = service.syncRemainingComments(SCOPE_ID, issue, "cursor-page-1");

            assertThat(synced).isEqualTo(1);
            verify(graphQlClient).documentName("GetIssueComments");
        }
    }
}
