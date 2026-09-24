package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.ExistingSummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueCommentConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPageInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GithubSummaryChannelTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private GithubPrNodeIdResolver prNodeIdResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GithubSummaryChannel channel;

    @BeforeEach
    void setUp() {
        channel = new GithubSummaryChannel(gitHubProvider, prNodeIdResolver, egressGuard);
    }

    @Test
    void postSummaryReturnsCommentNodeId() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", "IC_comment456");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(target, new FeedbackContent("body\n\nmarker", "marker"));

        assertNotNull(handle);
        assertThat(handle.externalId()).isEqualTo("IC_comment456");
        verify(spec).variable("body", "body\n\nmarker");
    }

    @Test
    void shouldBlockPostMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.post-summary");

        assertThatThrownBy(() -> channel.postSummary(
                        new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null),
                        new FeedbackContent("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void shouldBlockUpdateMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.update-summary");

        assertThatThrownBy(() -> channel.updateSummary(
                        new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null),
                        "IC_comment456",
                        new FeedbackContent("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void postSummaryThrowsOnRateLimit() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("rate limit critical");
    }

    @Test
    void postSummaryThrowsOnMalformedSubjectId() {
        FeedbackTarget target = new FeedbackTarget(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner-repo-without-hash", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Invalid GitHub PR subjectExternalId");
    }

    @Test
    void postSummaryThrowsOnMutationErrors() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse errorResponse = mock(ClientGraphQlResponse.class);
        when(errorResponse.getErrors()).thenReturn(List.of(mock(ResponseError.class)));
        when(spec.execute()).thenReturn(Mono.just(errorResponse));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("addComment failed");
    }

    @Test
    void updateSummary_editsInPlace_returnsEdited() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("updateIssueComment.issueComment.id", "IC_edited");
        when(spec.execute()).thenReturn(Mono.just(response));

        var outcome = channel.updateSummary(target, "IC_prior", new FeedbackContent("new body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.EDITED);
        assertNotNull(outcome.handle());
        assertThat(outcome.handle().externalId()).isEqualTo("IC_edited");
        verify(spec).variable("id", "IC_prior");
        verify(spec).variable("body", "new body\n\nmarker");
    }

    @Test
    void updateSummary_blankExternalId_throws() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        assertThatThrownBy(() -> channel.updateSummary(target, "  ", new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("external comment id is missing");
    }

    @Test
    void updateSummary_rateLimitCritical_isTransient_notRepost() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        var outcome = channel.updateSummary(target, "IC_prior", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.TRANSIENT);
    }

    @Test
    void updateSummary_deletedComment_isGone_soCallerReposts() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse errorResponse = mock(ClientGraphQlResponse.class);
        ResponseError err = mock(ResponseError.class);
        when(err.getMessage()).thenReturn("Could not resolve to a node with the global id of 'IC_prior'");
        when(errorResponse.getErrors()).thenReturn(List.of(err));
        when(spec.execute()).thenReturn(Mono.just(errorResponse));

        var outcome = channel.updateSummary(target, "IC_prior", new FeedbackContent("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(SummaryChannel.UpdateOutcome.Kind.GONE);
    }

    @Test
    void postSummary_nullResponse_throwsNullResponse() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.empty());

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Null response from AddPullRequestComment");
    }

    @Test
    void postSummary_nullCommentId_throwsNoCommentId() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", null);
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("No comment ID in AddPullRequestComment response");
    }

    @Test
    void postSummary_issueSubjectWithNonNumericId_fallsBackToPrValidation_throwsInvalidPrSubject() {
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/issues/x", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        assertThatThrownBy(() -> channel.postSummary(target, new FeedbackContent("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Invalid GitHub PR subjectExternalId");
    }

    @Test
    void parseIssueSubjectExternalId_rejectsSubjectWithoutNumber() {
        assertThatThrownBy(() -> GithubSummaryChannel.parseIssueSubjectExternalId("owner/issues/x"))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Invalid GitHub issue subjectExternalId");
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockGraphQlResponse(String fieldPath, @Nullable String value) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(fieldPath)).thenReturn(field);
        when(field.getValue()).thenReturn(value);
        lenient().when(response.getErrors()).thenReturn(List.of());
        return response;
    }

    @Test
    void issueAndPrSubjectIds_diverge_soTheChannelCanRouteThem_andRejectMalformedRepos() {
        assertThat(channel.formatIssueSubjectId("owner/repo", 7)).isEqualTo("owner/repo/issues/7");
        assertThat(channel.formatPullRequestSubjectId("owner/repo", 7)).isEqualTo("owner/repo#7");
        // GitHub requires a two-segment owner/repo — fail fast, not late in node-id resolution.
        assertThatThrownBy(() -> channel.formatIssueSubjectId("nope", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
        assertThatThrownBy(() -> channel.formatIssueSubjectId("a/b/c", 7)).isInstanceOf(IllegalArgumentException.class);
        // A blank owner or repo segment must also fail fast, not yield "owner/#42" / "owner//issues/42".
        assertThatThrownBy(() -> channel.formatPullRequestSubjectId("owner/", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
        assertThatThrownBy(() -> channel.formatIssueSubjectId("/repo", 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
    }

    @Test
    void postSummary_forAnIssueSubject_resolvesViaIssueNodeId_notThePrResolver() {
        // resolve() targets pullRequest and returns null for an issue, so an issue subject must route through
        // resolveIssue.
        FeedbackTarget target =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo/issues/42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolveIssue(1L, "owner", "repo", 42)).thenReturn("I_node789");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", "IC_issuecmt");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(target, new FeedbackContent("body", "marker"));

        assertThat(handle.externalId()).isEqualTo("IC_issuecmt");
        verify(prNodeIdResolver).resolveIssue(1L, "owner", "repo", 42);
        verify(prNodeIdResolver, never()).resolve(anyLong(), any(), any(), anyInt());
    }

    private static final FeedbackTarget PR_TARGET =
            new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

    private HttpGraphQlClient graphQlClient = mock(HttpGraphQlClient.class);

    private HttpGraphQlClient.RequestSpec mockRequestChain() {
        graphQlClient = mock(HttpGraphQlClient.class);
        HttpGraphQlClient.RequestSpec spec = mock(HttpGraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(graphQlClient);
        when(graphQlClient.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    /**
     * One page of a backwards (newest-end) walk. The forward pair is filled with opposite/invalid values
     * so a scan that reads {@code hasNextPage}/{@code endCursor} fails these tests instead of passing by coincidence.
     */
    private ClientGraphQlResponse mockCommentsPageResponse(
            String commentsPath, List<GHIssueComment> nodes, boolean hasPreviousPage, @Nullable String startCursor) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(commentsPath)).thenReturn(field);
        var pageInfo = GHPageInfo.builder()
                .setHasPreviousPage(hasPreviousPage)
                .setHasNextPage(!hasPreviousPage)
                .setEndCursor("forward-cursor-decoy");
        if (startCursor != null) pageInfo.setStartCursor(startCursor);
        GHIssueCommentConnection connection = GHIssueCommentConnection.builder()
                .setNodes(nodes)
                .setPageInfo(pageInfo.build())
                .setTotalCount(nodes.size())
                .build();
        when(field.toEntity(GHIssueCommentConnection.class)).thenReturn(connection);
        lenient().when(response.getErrors()).thenReturn(List.of());
        return response;
    }

    private static GHIssueComment comment(String id, String body) {
        return GHIssueComment.builder().setId(id).setBody(body).build();
    }

    @Test
    void findExistingSummary_pagesFromTheNewestEnd_neverForwards() {
        // The just-posted marker is newest; a forward walk could run out of budget before reaching it on a busy PR.
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), false, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        verify(graphQlClient).documentName("GetPullRequestCommentsNewest");
        verify(spec).variable("last", 100);
        verify(spec).variable("before", null);
        verify(spec, never()).variable(eq("first"), any());
        verify(spec, never()).variable(eq("after"), any());
    }

    @Test
    void findExistingSummary_issueSubject_pagesTheIssueConnectionFromTheNewestEnd() {
        FeedbackTarget issueTarget =
                new FeedbackTarget(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo/issues/42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response = mockCommentsPageResponse(
                "repository.issue.comments", List.of(comment("IC_1", "<!-- marker:job-1 -->body")), false, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        ExistingSummaryLookup result = channel.findExistingSummary(issueTarget, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        verify(graphQlClient).documentName("GetIssueCommentsNewest");
        verify(spec).variable("last", 100);
    }

    @Test
    void findExistingSummary_markerOnTheNewestPage_isFoundInOneRequest() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", "unrelated"), comment("IC_2", "<!-- marker:job-1 -->body")),
                true,
                "start-cursor-1");
        when(spec.execute()).thenReturn(Mono.just(newestPage));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("IC_2");
        verify(spec, times(1)).execute();
    }

    @Test
    void findExistingSummary_walkedBackToTheOldestComment_noMatch_isAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse oldestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", "also unrelated")),
                false, // hasPreviousPage=false — the oldest comment was reached, every comment was scanned
                null);
        when(spec.execute()).thenReturn(Mono.just(newestPage)).thenReturn(Mono.just(oldestPage));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.ABSENT);
        verify(spec, times(2)).execute();
        verify(spec).variable("before", "start-cursor-1");
    }

    @Test
    void findExistingSummary_pageBudgetExhaustedWithOlderCommentsLeft_isUnknown_notAbsent() {
        // A marker older than the scanned pages must NOT be reported ABSENT — the caller would post a duplicate.
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page1 = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_3", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse page2 = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-2");
        ClientGraphQlResponse page3 = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", "unrelated")),
                true, // still older comments left when the budget runs out
                "start-cursor-3");
        when(spec.execute())
                .thenReturn(Mono.just(page1))
                .thenReturn(Mono.just(page2))
                .thenReturn(Mono.just(page3));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
        verify(spec, times(3)).execute();
        verify(spec).variable("before", "start-cursor-2");
    }

    @Test
    void findExistingSummary_matchOnAnOlderPage_isFound() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse olderPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "<!-- marker:job-1 -->body")), false, null);
        when(spec.execute()).thenReturn(Mono.just(newestPage)).thenReturn(Mono.just(olderPage));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalId()).isEqualTo("IC_1");
        verify(spec, times(2)).execute();
    }

    @Test
    void findExistingSummary_missingCursorMidWalk_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), true, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
        verify(spec, times(1)).execute();
    }

    @Test
    void findExistingSummary_rateLimitCritical_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }

    @Test
    void findExistingSummary_transportError_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        HttpGraphQlClient.RequestSpec spec = mockRequestChain();
        when(spec.execute()).thenThrow(new RuntimeException("boom"));

        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "<!-- marker:job-1 -->");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }

    @Test
    void findExistingSummary_blankMarker_isUnknown() {
        ExistingSummaryLookup result = channel.findExistingSummary(PR_TARGET, "  ");

        assertThat(result.kind()).isEqualTo(ExistingSummaryLookup.Kind.UNKNOWN);
    }
}
