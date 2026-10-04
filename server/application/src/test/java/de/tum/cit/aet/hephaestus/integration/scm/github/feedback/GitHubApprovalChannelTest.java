package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubApprovalChannelTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private GitHubPrNodeIdResolver prNodeIdResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitHubApprovalChannel channel;

    @BeforeEach
    void setUp() {
        channel = new GitHubApprovalChannel(gitHubProvider, prNodeIdResolver, egressGuard);
    }

    @Test
    void approveInvokesMutation() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName("ApprovePullRequest")).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField idField = mock(ClientResponseField.class);
        when(response.field("addPullRequestReview.pullRequestReview.id")).thenReturn(idField);
        when(idField.getValue()).thenReturn("PRR_xyz");
        lenient().when(response.getErrors()).thenReturn(List.of());
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatCode(() -> channel.approve(target, "looks good")).doesNotThrowAnyException();

        verify(client).documentName("ApprovePullRequest");
        verify(spec).variable(eq("pullRequestId"), eq("PR_node123"));
        verify(spec).variable(eq("body"), eq("looks good"));
    }

    @Test
    void shouldBlockApprovalMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.approve");

        assertThatThrownBy(() -> channel.approve(
                        new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null),
                        "looks good"))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verifyNoInteractions(prNodeIdResolver);
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void approveThrowsOnRateLimit() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        assertThatThrownBy(() -> channel.approve(target, "ok"))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("rate limit is critical");
    }

    @Test
    void approveThrowsOnMutationErrors() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        when(response.getErrors()).thenReturn(List.of(mock(ResponseError.class)));
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> channel.approve(target, "ok"))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("ApprovePullRequest failed");
    }
}
