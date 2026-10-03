package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitHubPrNodeIdResolverTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private HttpGraphQlClient.RequestSpec spec;

    private GitHubPrNodeIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new GitHubPrNodeIdResolver(gitHubProvider);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
    }

    @Test
    void resolve_usesPullRequestDocumentAndField_returnsNodeId() {
        // resolve() must drive the GetPullRequestNodeId query and read the PR-specific field path; an issue's
        // repository.pullRequest is null, so the field path is load-bearing for routing.
        ClientGraphQlResponse response = mockGraphQlResponse("repository.pullRequest.id", "PR_node123");
        when(spec.execute()).thenReturn(Mono.just(response));

        String nodeId = resolver.resolve(1L, "owner", "repo", 42);

        assertThat(nodeId).isEqualTo("PR_node123");
        verify(client).documentName("GetPullRequestNodeId");
        verify(spec).variable("owner", "owner");
        verify(spec).variable("name", "repo");
        verify(spec).variable("number", 42);
        verify(response).field("repository.pullRequest.id");
        verify(gitHubProvider).trackRateLimit(1L, response);
    }

    @Test
    void resolveIssue_usesIssueDocumentAndField_returnsNodeId() {
        // resolveIssue() must use the issue document + repository.issue.id, never the PR field, because GitHub
        // addresses PRs and issues identically as owner/repo#N.
        ClientGraphQlResponse response = mockGraphQlResponse("repository.issue.id", "I_node789");
        when(spec.execute()).thenReturn(Mono.just(response));

        String nodeId = resolver.resolveIssue(1L, "owner", "repo", 7);

        assertThat(nodeId).isEqualTo("I_node789");
        verify(client).documentName("GetIssueNodeId");
        verify(spec).variable("number", 7);
        verify(response).field("repository.issue.id");
        verify(gitHubProvider).trackRateLimit(1L, response);
    }

    @Test
    void resolve_nullResponse_throwsWithReference() {
        // A null (timed-out / empty) response must fail loudly with the owner/repo#N reference so the caller can
        // mark delivery failed, and rate-limit tracking must NOT be attempted on a null response.
        when(spec.execute()).thenReturn(Mono.empty());

        assertThatThrownBy(() -> resolver.resolve(1L, "owner", "repo", 42))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Null response")
                .hasMessageContaining("owner/repo#42");
    }

    @Test
    void resolve_nullNodeId_throwsNotFoundWithErrors() {
        // A response whose id field is null means the PR was not found; the thrown message must surface both the
        // reference and the GraphQL errors so the failure is diagnosable.
        ClientGraphQlResponse response = mockGraphQlResponse("repository.pullRequest.id", null);
        ResponseError err = mock(ResponseError.class);
        when(err.toString()).thenReturn("NOT_FOUND");
        when(response.getErrors()).thenReturn(List.<ResponseError>of(err));
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> resolver.resolve(1L, "owner", "repo", 42))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("PR not found via GraphQL")
                .hasMessageContaining("owner/repo#42")
                .hasMessageContaining("errors=");
    }

    @Test
    void resolveIssue_nullNodeId_throwsIssueNotFound() {
        ClientGraphQlResponse response = mockGraphQlResponse("repository.issue.id", null);
        when(response.getErrors()).thenReturn(List.of());
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> resolver.resolveIssue(1L, "owner", "repo", 7))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Issue not found via GraphQL")
                .hasMessageContaining("owner/repo#7");
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockGraphQlResponse(String fieldPath, @Nullable String value) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(eq(fieldPath))).thenReturn(field);
        when(field.<String>getValue()).thenReturn(value);
        lenient().when(response.getErrors()).thenReturn(List.of());
        return response;
    }
}
