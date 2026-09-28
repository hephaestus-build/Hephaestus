package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler.HandleResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

/**
 * The reviewer refresh a caller runs after an incremental merge request sync. A GitLab approval, request for changes or
 * re-request is not known to advance the merge request's {@code updatedAt}, so the incremental sync alone would
 * never read again a reviewer state whose webhook was missed. The refresh reads every open merge request's
 * reviewers in one listing, and never applies a list it could not read whole.
 */
class GitLabOpenMergeRequestReviewerRefreshTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 100L;
    private static final String FULL_PATH = "hephaestustest/demo-repository";
    private static final String LISTING = "GetProjectOpenMergeRequestReviewers";

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabGraphQlResponseHandler responseHandler;

    @Mock
    private GitLabMergeRequestProcessor mergeRequestProcessor;

    @Mock
    private GitLabDiscussionSyncService discussionSyncService;

    @Mock
    private GitLabClosingIssueClient closingIssueClient;

    @Mock
    private HttpGraphQlClient client;

    @Mock
    private HttpGraphQlClient.RequestSpec requestSpec;

    private GitLabMergeRequestSyncService service;

    private final Deque<Mono<ClientGraphQlResponse>> scriptedResponses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        service = new GitLabMergeRequestSyncService(
                graphQlClientProvider,
                responseHandler,
                mergeRequestProcessor,
                discussionSyncService,
                closingIssueClient,
                new GitLabProperties(
                        "https://gitlab.lrz.de",
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ZERO,
                        Duration.ofMinutes(5)));
        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(invocation -> scriptedResponses.isEmpty() ? Mono.empty() : scriptedResponses.poll());
        lenient()
                .when(responseHandler.handle(any(), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.CONTINUE, null));
    }

    @Test
    void shouldApplyEachOpenMergeRequestsReviewersWhenOneListingReadsThemAll() {
        scriptedResponses.add(Mono.just(openMergeRequests(List.of(
                mergeRequest("3", List.of(reviewer(6, "user1", "APPROVED")), false),
                mergeRequest("4", List.of(reviewer(8, "user3", "UNREVIEWED")), false)))));

        SyncResult result = service.refreshOpenMergeRequestReviewers(SCOPE_ID, repository());

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        verify(client).documentName(LISTING);
        verify(client, never()).documentName("GetMergeRequestReviewers");
        ArgumentCaptor<List<GitLabMergeRequestProcessor.SyncReviewerData>> reviewers = captor();
        verify(mergeRequestProcessor).applySyncedReviewers(any(), eq(3), reviewers.capture(), any());
        assertThat(reviewers.getValue())
                .extracting(
                        reviewer -> reviewer.user().username(),
                        GitLabMergeRequestProcessor.SyncReviewerData::reviewState)
                .containsExactly(org.assertj.core.api.Assertions.tuple("user1", "APPROVED"));
        verify(mergeRequestProcessor).applySyncedReviewers(any(), eq(4), anyList(), any());
    }

    @Test
    void shouldLeaveAMergeRequestsReviewersAloneWhenTheirListCannotBeReadWhole() {
        scriptedResponses.add(Mono.just(
                openMergeRequests(List.of(mergeRequest("3", List.of(reviewer(6, "user1", "APPROVED")), true)))));
        ClientGraphQlResponse refused = mock(ClientGraphQlResponse.class);
        when(responseHandler.handle(eq(refused), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));
        scriptedResponses.add(Mono.just(refused));

        SyncResult result = service.refreshOpenMergeRequestReviewers(SCOPE_ID, repository());

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED_WITH_WARNINGS);
        verify(client).documentName("GetMergeRequestReviewers");
        verify(mergeRequestProcessor, never()).applySyncedReviewers(any(), anyInt(), anyList(), any());
    }

    @Test
    void shouldReadEveryPageWhenMoreMergeRequestsAreOpenThanOnePageHolds() {
        scriptedResponses.add(Mono.just(openMergeRequests(
                List.of(mergeRequest("3", List.of(reviewer(6, "user1", "APPROVED")), false)), true)));
        scriptedResponses.add(Mono.just(openMergeRequests(
                List.of(mergeRequest("4", List.of(reviewer(8, "user3", "UNREVIEWED")), false)), false)));

        SyncResult result = service.refreshOpenMergeRequestReviewers(SCOPE_ID, repository());

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
        verify(client, times(2)).documentName(LISTING);
        verify(requestSpec).variable("after", "page-2");
        verify(mergeRequestProcessor).applySyncedReviewers(any(), eq(3), anyList(), any());
        verify(mergeRequestProcessor).applySyncedReviewers(any(), eq(4), anyList(), any());
    }

    @Test
    void shouldStoreTheOtherMergeRequestsWhenOneCannotBeStored() {
        scriptedResponses.add(Mono.just(openMergeRequests(List.of(
                mergeRequest("3", List.of(reviewer(6, "user1", "APPROVED")), false),
                mergeRequest("4", List.of(reviewer(8, "user3", "UNREVIEWED")), false)))));
        when(mergeRequestProcessor.applySyncedReviewers(any(), eq(3), anyList(), any()))
                .thenThrow(new IllegalStateException("the database refused the row"));
        when(mergeRequestProcessor.applySyncedReviewers(any(), eq(4), anyList(), any()))
                .thenReturn(true);

        SyncResult result = service.refreshOpenMergeRequestReviewers(SCOPE_ID, repository());

        assertThat(result).isEqualTo(SyncResult.completedWithWarnings(1));
        verify(graphQlClientProvider, never()).recordFailure(any());
    }

    @Test
    void shouldCountAgainstGitLabsCircuitBreakerWhenGitLabRefusesTheListing() {
        ClientGraphQlResponse refused = mock(ClientGraphQlResponse.class);
        when(responseHandler.handle(eq(refused), anyString(), any()))
                .thenReturn(new HandleResult(HandleResult.Action.ABORT, null));
        scriptedResponses.add(Mono.just(refused));

        SyncResult result = service.refreshOpenMergeRequestReviewers(SCOPE_ID, repository());

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED_WITH_WARNINGS);
        verify(graphQlClientProvider).recordFailure(any());
        verify(mergeRequestProcessor, never()).applySyncedReviewers(any(), anyInt(), anyList(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<GitLabMergeRequestProcessor.SyncReviewerData>> captor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        return repository;
    }

    private static Map<String, Object> reviewer(long id, String username, String state) {
        return Map.of(
                "id",
                "gid://gitlab/User/" + id,
                "username",
                username,
                "name",
                username,
                "avatarUrl",
                "https://gitlab.lrz.de/uploads/avatar.png",
                "webUrl",
                "https://gitlab.lrz.de/" + username,
                "mergeRequestInteraction",
                Map.of("reviewState", state));
    }

    /** An open merge request's node; {@code overflowing} says its reviewers run past the first page. */
    private static Map<String, Object> mergeRequest(
            String iid, List<Map<String, Object>> reviewers, boolean overflowing) {
        return Map.of(
                "iid",
                iid,
                "reviewers",
                Map.of(
                        "count",
                        overflowing ? 21 : reviewers.size(),
                        "pageInfo",
                        Map.of("hasNextPage", overflowing, "endCursor", overflowing ? "next" : "last"),
                        "nodes",
                        reviewers));
    }

    private ClientGraphQlResponse openMergeRequests(List<Map<String, Object>> nodes) {
        return openMergeRequests(nodes, false);
    }

    private ClientGraphQlResponse openMergeRequests(List<Map<String, Object>> nodes, boolean hasNextPage) {
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        return page(nodes, hasNextPage);
    }

    private static ClientGraphQlResponse page(List<Map<String, Object>> nodes, boolean hasNextPage) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.isValid()).thenReturn(true);
        ClientResponseField nodesField = mock(ClientResponseField.class);
        lenient().doReturn(nodes).when(nodesField).toEntityList(Map.class);
        lenient().when(response.field("project.mergeRequests.nodes")).thenReturn(nodesField);
        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        lenient()
                .when(pageInfoField.toEntity(GitLabPageInfo.class))
                .thenReturn(new GitLabPageInfo(hasNextPage, hasNextPage ? "page-2" : null));
        lenient().when(response.field("project.mergeRequests.pageInfo")).thenReturn(pageInfoField);
        return response;
    }
}
