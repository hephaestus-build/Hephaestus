package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GraphQlPaginationHelper;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubClosingIssueClientTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider clients;

    @Mock
    private GitHubSyncProperties properties;

    @Mock
    private GitHubGraphQlSyncCoordinator coordinator;

    @Mock
    private HttpGraphQlClient graphQl;

    @Mock
    private GraphQlClient.RequestSpec request;

    @Mock
    private ClientGraphQlResponse response;

    @Mock
    private ClientResponseField field;

    @Test
    void shouldReadEveryPageBeforeReplacingTheEmbeddedReferences() {
        GHPullRequest pullRequest = pullRequest();
        GHIssueConnection second = page(51, 150, true);
        second.getPageInfo().setEndCursor("second");
        stubPage(second);
        when(field.toEntity(GHIssueConnection.class)).thenReturn(second, page(151, 151, false));

        closingIssues().completeReferences(42L, pullRequest);

        assertThat(pullRequest.getClosingIssuesReferences().getNodes())
                .extracting(GHIssue::getNumber)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 151).boxed().toList());
        assertThat(pullRequest.getClosingIssuesReferences().getPageInfo().getHasNextPage())
                .isFalse();
    }

    @Test
    void shouldKeepTheEmbeddedPageIncompleteWhenAFollowingPageFails() {
        GHPullRequest pullRequest = pullRequest();
        stubTransport();
        when(request.execute()).thenReturn(Mono.error(new IllegalStateException("unavailable")));

        assertThatThrownBy(() -> closingIssues().completeReferences(42L, pullRequest))
                .isInstanceOf(IllegalStateException.class);
        assertThat(pullRequest.getClosingIssuesReferences().getNodes()).hasSize(50);
        assertThat(pullRequest.getClosingIssuesReferences().getPageInfo().getHasNextPage())
                .isTrue();
    }

    @Test
    void shouldStopWhenTheProviderRepeatsACursor() {
        GHPullRequest pullRequest = pullRequest();
        stubPage(page(51, 51, true));

        assertThatThrownBy(() -> closingIssues().completeReferences(42L, pullRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped before the end");
        assertThat(pullRequest.getClosingIssuesReferences().getNodes()).hasSize(50);
    }

    @Test
    void shouldStopWhenPaginationMetadataIsMissing() {
        GHPullRequest pullRequest = pullRequest();
        GHIssueConnection incomplete = new GHIssueConnection();
        incomplete.setNodes(pullRequest.getClosingIssuesReferences().getNodes());
        pullRequest.setClosingIssuesReferences(incomplete);

        assertThatThrownBy(() -> closingIssues().completeReferences(42L, pullRequest))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldStopWhenTheNextCursorIsMissing() {
        GHPullRequest pullRequest = pullRequest();
        GHPageInfo info = new GHPageInfo();
        info.setHasNextPage(true);
        pullRequest.getClosingIssuesReferences().setPageInfo(info);

        assertThatThrownBy(() -> closingIssues().completeReferences(42L, pullRequest))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSkipANullPullRequestNode() {
        closingIssues().completeReferences(42L, null);
    }

    private GitHubClosingIssueClient closingIssues() {
        return new GitHubClosingIssueClient(clients, properties, new GraphQlPaginationHelper(clients, coordinator));
    }

    private void stubTransport() {
        when(clients.forScope(42L)).thenReturn(graphQl);
        when(graphQl.documentName("GetPullRequestClosingIssues")).thenReturn(request);
        when(request.variable(anyString(), any())).thenReturn(request);
        when(properties.extendedGraphqlTimeout()).thenReturn(Duration.ofSeconds(1));
    }

    private void stubPage(GHIssueConnection page) {
        stubTransport();
        when(request.execute()).thenReturn(Mono.just(response));
        when(response.isValid()).thenReturn(true);
        when(response.field("node.closingIssuesReferences")).thenReturn(field);
        when(field.toEntity(GHIssueConnection.class)).thenReturn(page);
    }

    private static GHPullRequest pullRequest() {
        GHPullRequest pullRequest = new GHPullRequest();
        pullRequest.setId("PR_example");
        pullRequest.setClosingIssuesReferences(page(1, 50, true));
        return pullRequest;
    }

    private static GHIssueConnection page(int from, int to, boolean hasNextPage) {
        GHIssueConnection page = new GHIssueConnection();
        page.setNodes(IntStream.rangeClosed(from, to)
                .mapToObj(number -> {
                    GHIssue issue = new GHIssue();
                    issue.setNumber(number);
                    return issue;
                })
                .toList());
        GHPageInfo info = new GHPageInfo();
        info.setHasNextPage(hasNextPage);
        info.setEndCursor("cursor");
        page.setPageInfo(info);
        return page;
    }
}
