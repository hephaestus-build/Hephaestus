package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITHUB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitHubSummaryWriterTest extends BaseUnitTest {

    @Mock
    private GitHubGraphQlClientProvider gitHubProvider;

    @Mock
    private GitHubPrNodeIdResolver prNodeIdResolver;

    @Mock
    private OutboundEgressGuard egressGuard;

    private GitHubSummaryWriter channel;

    @BeforeEach
    void setUp() {
        channel =
                new GitHubSummaryWriter(gitHubProvider, prNodeIdResolver, egressGuard, ScmFeedbackFixtures.resolver());
    }

    @Test
    void postSummaryReturnsCommentNodeId() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", "IC_comment456");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle =
                channel.postSummary(target, ScmFeedbackFixtures.summary("body for @alice\n\nmarker", "marker"));

        assertNotNull(handle);
        assertThat(handle.externalRef()).isEqualTo("IC_comment456");
        assertThat(handle.url()).isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-987654");
        verify(spec).variable("body", "body for <code>@alice</code>\n\nmarker");
    }

    @Test
    void shouldBlockPostMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.post-summary");

        assertThatThrownBy(() -> channel.postSummary(
                        ScmFeedbackFixtures.target(
                                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null),
                        ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void shouldBlockUpdateMutationWhenSilentModeIsEngaged() {
        doThrow(new OutboundEgressSuppressedException("test"))
                .when(egressGuard)
                .requireDeliveryAllowed("github.update-summary");

        assertThatThrownBy(() -> channel.updateSummary(
                        ScmFeedbackFixtures.target(
                                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null),
                        new SummaryHandle("IC_comment456", null),
                        ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(OutboundEgressSuppressedException.class);
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void postSummaryThrowsOnRateLimit() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("rate limit is critical");
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void anIssueThatCannotBeResolvedIsNotSent() {
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo/issues/42", null);
        when(prNodeIdResolver.resolveIssue(1L, "owner", "repo", 42))
                .thenThrow(new FeedbackDeliveryException("Issue not found via GraphQL"));

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("Issue not found");
        verify(gitHubProvider, never()).forScope(anyLong());
    }

    @Test
    void postSummaryThrowsOnMalformedSubjectId() {
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner-repo-without-hash", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("Invalid GitHub PR subjectExternalId");
    }

    @Test
    void postSummaryThrowsOnMutationErrors() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse errorResponse = mock(ClientGraphQlResponse.class);
        when(errorResponse.getErrors()).thenReturn(List.of(mock(ResponseError.class)));
        when(spec.execute()).thenReturn(Mono.just(errorResponse));

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .isNotInstanceOf(FeedbackNotSentException.class)
                .hasMessageContaining("addComment failed");
    }

    @Test
    void updateSummary_editsInPlace_returnsEdited() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("updateIssueComment.issueComment.id", "IC_edited");
        when(spec.execute()).thenReturn(Mono.just(response));

        var outcome = channel.updateSummary(
                target,
                new SummaryHandle("IC_prior", null),
                ScmFeedbackFixtures.summary("new body for @alice", "marker"));

        assertThat(outcome.kind()).isEqualTo(CopyUpdateKind.EDITED);
        assertNotNull(outcome.externalRef());
        assertThat(outcome.externalRef()).isEqualTo("IC_edited");
        assertThat(outcome.externalUrl()).isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-987654");
        verify(spec).variable("id", "IC_prior");
        // An edit that adds a name notifies that person, so the edited body mentions no one either.
        verify(spec).variable("body", "new body for <code>@alice</code>\n\nmarker");
    }

    @Test
    void updateSummary_blankExternalId_throws() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        assertThatThrownBy(() -> channel.updateSummary(
                        target, new SummaryHandle("  ", null), ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Summary reference is blank");
    }

    @Test
    void updateSummary_rateLimitCritical_isTransient_notRepost() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        var outcome = channel.updateSummary(
                target, new SummaryHandle("IC_prior", null), ScmFeedbackFixtures.summary("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(CopyUpdateKind.TRANSIENT);
    }

    @Test
    void updateSummary_deletedComment_isGone_soCallerReposts() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);

        ClientGraphQlResponse errorResponse = mock(ClientGraphQlResponse.class);
        ResponseError err = mock(ResponseError.class);
        when(err.getMessage()).thenReturn("Could not resolve to a node with the global id of 'IC_prior'");
        when(errorResponse.getErrors()).thenReturn(List.of(err));
        when(spec.execute()).thenReturn(Mono.just(errorResponse));

        var outcome = channel.updateSummary(
                target, new SummaryHandle("IC_prior", null), ScmFeedbackFixtures.summary("body", "marker"));

        assertThat(outcome.kind()).isEqualTo(CopyUpdateKind.GONE);
    }

    @Test
    void postSummary_nullResponse_throwsNullResponse() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        when(spec.execute()).thenReturn(Mono.empty());

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("The AddPullRequestComment mutation returned no response");
    }

    @Test
    void postSummary_nullCommentId_throwsNoCommentId() {
        FeedbackTarget target =
                ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolve(1L, "owner", "repo", 42)).thenReturn("PR_node123");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", null);
        when(spec.execute()).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("No comment ID in AddPullRequestComment response");
    }

    @Test
    void postSummary_issueSubjectWithNonNumericId_fallsBackToPrValidation_throwsInvalidPrSubject() {
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/issues/x", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);

        assertThatThrownBy(() -> channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker")))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Invalid GitHub PR subjectExternalId");
    }

    @Test
    void parseIssueSubjectExternalId_rejectsSubjectWithoutNumber() {
        assertThatThrownBy(() -> GitHubSummaryWriter.parseIssueSubjectExternalId("owner/issues/x"))
                .isInstanceOf(FeedbackDeliveryException.class)
                .hasMessageContaining("Invalid GitHub issue subjectExternalId");
    }

    @SuppressWarnings("unchecked")
    private ClientGraphQlResponse mockGraphQlResponse(String fieldPath, @Nullable String value) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(fieldPath)).thenReturn(field);
        when(field.getValue()).thenReturn(value);
        ClientResponseField urlField = mock(ClientResponseField.class);
        lenient().when(response.field(fieldPath.replace(".id", ".url"))).thenReturn(urlField);
        lenient().when(urlField.getValue()).thenReturn("https://github.com/owner/repo/pull/42#issuecomment-987654");
        lenient().when(response.getErrors()).thenReturn(List.of());
        return response;
    }

    @Test
    void postSummary_forAnIssueSubject_resolvesViaIssueNodeId_notThePrResolver() {
        // resolve() targets pullRequest and returns null for an issue, so an issue subject must
        // route through
        // resolveIssue.
        FeedbackTarget target = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo/issues/42", null);

        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        when(prNodeIdResolver.resolveIssue(1L, "owner", "repo", 42)).thenReturn("I_node789");

        HttpGraphQlClient client = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(client);
        when(client.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        ClientGraphQlResponse response = mockGraphQlResponse("addComment.commentEdge.node.id", "IC_issuecmt");
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryHandle handle = channel.postSummary(target, ScmFeedbackFixtures.summary("body", "marker"));

        assertThat(handle.externalRef()).isEqualTo("IC_issuecmt");
        verify(prNodeIdResolver).resolveIssue(1L, "owner", "repo", 42);
        verify(prNodeIdResolver, never()).resolve(anyLong(), any(), any(), anyInt());
    }

    private static final FeedbackTarget PR_TARGET =
            ScmFeedbackFixtures.target(new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo#42", null);

    private HttpGraphQlClient graphQlClient = mock(HttpGraphQlClient.class);

    private GraphQlClient.RequestSpec mockRequestChain() {
        graphQlClient = mock(HttpGraphQlClient.class);
        GraphQlClient.RequestSpec spec = mock(GraphQlClient.RequestSpec.class);
        when(gitHubProvider.forScope(1L)).thenReturn(graphQlClient);
        when(graphQlClient.documentName(any())).thenReturn(spec);
        when(spec.variable(any(), any())).thenReturn(spec);
        return spec;
    }

    private static final String MARKER = "<!-- marker:job-1 -->";

    private static final SummaryContent EXPECTED = ScmFeedbackFixtures.summary("Summary text", MARKER);

    private static final String EXACT = EXPECTED.externalBody();

    private ClientGraphQlResponse mockCommentsPageResponse(
            String commentsPath,
            List<Map<String, Object>> nodes,
            @Nullable Boolean hasPreviousPage,
            @Nullable String startCursor) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        ClientResponseField field = mock(ClientResponseField.class);
        when(response.field(commentsPath)).thenReturn(field);
        Map<String, Object> pageInfo = new HashMap<>();
        pageInfo.put("hasPreviousPage", hasPreviousPage);
        pageInfo.put("startCursor", startCursor);
        Map<String, Object> connection = Map.of("nodes", nodes, "pageInfo", pageInfo);
        assertVendorCouldReturn(
                GITHUB,
                commentsPath.startsWith("repository.issue") ? "GetIssueCommentsNewest" : "GetPullRequestCommentsNewest",
                commentsPath,
                connection);
        when(field.getValue()).thenReturn(connection);
        lenient().when(response.getErrors()).thenReturn(List.of());
        return response;
    }

    private static Map<String, Object> comment(String id, String body) {
        return comment(id, body, true);
    }

    private static Map<String, Object> comment(String id, String body, boolean viewerDidAuthor) {
        return Map.of(
                "id",
                id,
                "body",
                body,
                "url",
                "https://github.com/owner/repo/pull/42#issuecomment-" + id,
                "viewerDidAuthor",
                viewerDidAuthor);
    }

    @Test
    void findExistingSummary_choosesTheViewersExactCopyOverANewerHumanCopy() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", EXACT), comment("IC_2", EXACT, false)),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalRef()).isEqualTo("IC_1");
    }

    @Test
    void findExistingSummary_aMarkedCommentThatIsNotTheCopy_isUnknown_notFoundOrAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", EXACT + " edited"), comment("IC_2", EXACT, false)),
                false,
                null);
        when(spec.execute()).thenReturn(Mono.just(page));

        assertThat(channel.findExistingSummary(PR_TARGET, EXPECTED).presence()).isEqualTo(LookupPresence.UNKNOWN);
    }

    @Test
    void findExistingSummary_aPageWithoutItsPreviousPageFlag_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), null, null);
        when(spec.execute()).thenReturn(Mono.just(page));

        assertThat(channel.findExistingSummary(PR_TARGET, EXPECTED).presence()).isEqualTo(LookupPresence.UNKNOWN);
    }

    @Test
    void findExistingSummary_pagesFromTheNewestEnd_neverForwards() {
        // Read newest-first so pagination limits cannot hide a just-posted marker.
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), false, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        channel.findExistingSummary(PR_TARGET, EXPECTED);

        verify(graphQlClient).documentName("GetPullRequestCommentsNewest");
        verify(spec).variable("last", 100);
        verify(spec).variable("before", null);
        verify(spec, never()).variable(eq("first"), any());
        verify(spec, never()).variable(eq("after"), any());
    }

    @Test
    void findExistingSummary_issueSubject_pagesTheIssueConnectionFromTheNewestEnd() {
        FeedbackTarget issueTarget = ScmFeedbackFixtures.target(
                new IntegrationRef(IntegrationKind.GITHUB, 1L, null), "owner/repo/issues/42", null);
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response =
                mockCommentsPageResponse("repository.issue.comments", List.of(comment("IC_1", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryLookup result = channel.findExistingSummary(issueTarget, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.FOUND);
        verify(graphQlClient).documentName("GetIssueCommentsNewest");
        verify(spec).variable("last", 100);
    }

    @Test
    void findExistingSummary_markerOnTheNewestPage_isFoundInOneRequest() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments",
                List.of(comment("IC_1", "unrelated"), comment("IC_2", EXACT)),
                true,
                "start-cursor-1");
        when(spec.execute()).thenReturn(Mono.just(newestPage));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalRef()).isEqualTo("IC_2");
        assertThat(result.handle().url()).isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-IC_2");
        verify(spec, times(1)).execute();
    }

    @Test
    void findExistingSummary_walkedBackToTheOldestComment_noMatch_isAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse oldestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "also unrelated")), false, null);
        when(spec.execute()).thenReturn(Mono.just(newestPage)).thenReturn(Mono.just(oldestPage));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.ABSENT);
        verify(spec, times(2)).execute();
        verify(spec).variable("before", "start-cursor-1");
    }

    @Test
    void findExistingSummary_pageBudgetExhaustedWithOlderCommentsLeft_isUnknown_notAbsent() {
        // A marker older than the scanned pages must NOT be reported ABSENT — the caller would post
        // a duplicate.
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse page1 = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_3", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse page2 = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-2");
        ClientGraphQlResponse page3 = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), true, "start-cursor-3");
        when(spec.execute())
                .thenReturn(Mono.just(page1))
                .thenReturn(Mono.just(page2))
                .thenReturn(Mono.just(page3));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.UNKNOWN);
        verify(spec, times(3)).execute();
        verify(spec).variable("before", "start-cursor-2");
    }

    @Test
    void findExistingSummary_matchOnAnOlderPage_isFound() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse newestPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_2", "unrelated")), true, "start-cursor-1");
        ClientGraphQlResponse olderPage = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(newestPage)).thenReturn(Mono.just(olderPage));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.FOUND);
        assertNotNull(result.handle());
        assertThat(result.handle().externalRef()).isEqualTo("IC_1");
        verify(spec, times(2)).execute();
    }

    @Test
    void findExistingSummary_missingCursorMidWalk_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse response = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(comment("IC_1", "unrelated")), true, null);
        when(spec.execute()).thenReturn(Mono.just(response));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.UNKNOWN);
        verify(spec, times(1)).execute();
    }

    @Test
    void findExistingSummary_rateLimitCritical_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(true);

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.UNKNOWN);
    }

    @Test
    void findExistingSummary_transportError_isUnknown_notAbsent() {
        when(gitHubProvider.isRateLimitCritical(1L)).thenReturn(false);
        GraphQlClient.RequestSpec spec = mockRequestChain();
        when(spec.execute()).thenThrow(new RuntimeException("boom"));

        SummaryLookup result = channel.findExistingSummary(PR_TARGET, EXPECTED);

        assertThat(result.presence()).isEqualTo(LookupPresence.UNKNOWN);
    }

    @Test
    void unreadableBodyPreventsAbsenceButDoesNotHideAnExactCopy() {
        GraphQlClient.RequestSpec spec = mockRequestChain();
        ClientGraphQlResponse unreadable =
                mockCommentsPageResponse("repository.pullRequest.comments", List.of(Map.of("id", "IC_1")), false, null);
        ClientGraphQlResponse found = mockCommentsPageResponse(
                "repository.pullRequest.comments", List.of(Map.of("id", "IC_1"), comment("IC_2", EXACT)), false, null);
        when(spec.execute()).thenReturn(Mono.just(unreadable)).thenReturn(Mono.just(found));
        assertThat(channel.findExistingSummary(PR_TARGET, EXPECTED).presence()).isEqualTo(LookupPresence.UNKNOWN);
        assertThat(channel.findExistingSummary(PR_TARGET, EXPECTED).presence()).isEqualTo(LookupPresence.FOUND);
    }

    @Test
    void blankNativeSummaryIdCannotAcknowledgeDelivery() {
        assertThatThrownBy(() -> new SummaryHandle(" ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findExistingSummary_blankMarker_isUnknown() {
        SummaryLookup result = channel.findExistingSummary(PR_TARGET, ScmFeedbackFixtures.summary("body", "  "));

        assertThat(result.presence()).isEqualTo(LookupPresence.UNKNOWN);
    }
}
