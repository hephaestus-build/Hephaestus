package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import reactor.core.publisher.Mono;

class GitLabMergeRequestSyncServiceTest extends BaseUnitTest {

    private static final Long SCOPE_ID = 100L;
    private static final String FULL_PATH = "hephaestustest/demo-repository";
    private static final String LISTING = "GetProjectMergeRequests";
    private static final String REVIEWER_PAGES = "GetMergeRequestReviewers";

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

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

    private final Deque<ClientGraphQlResponse> scriptedResponses = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        service = new GitLabMergeRequestSyncService(
                graphQlClientProvider,
                new GitLabGraphQlResponseHandler(mock(GitLabExceptionClassifier.class)),
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
                .thenAnswer(
                        invocation -> scriptedResponses.isEmpty() ? Mono.empty() : Mono.just(scriptedResponses.poll()));
    }

    @Test
    void shouldReadDiscussionsOfAnApprovedOrSettledMergeRequestWithNoUserNotes() {
        // userNotesCount excludes approval system notes, so zero user notes must not skip discussions.
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, true, "opened"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "merged"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "closed"))
                .isTrue();
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(3, false, "opened"))
                .isTrue();
    }

    @Test
    void shouldSpareTheRequestForAnUntouchedOpenMergeRequest() {
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(0, false, "opened"))
                .isFalse();
    }

    @Test
    void shouldHandOnEveryReviewerAndTheirStateWhenTheFollowUpPagesReachTheListsEnd() {
        scriptedResponses.add(listing(reviewers(21, true, "first-end", twentyReviewersFrom(100))));
        scriptedResponses.add(reviewerPage(reviewers(21, false, "second-end", List.of(reviewer(8, "user3", null)))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        List<GitLabMergeRequestProcessor.SyncReviewerData> synced = Objects.requireNonNull(syncedReviewers());
        assertThat(synced).hasSize(21);
        assertThat(synced)
                .extracting(
                        reviewer -> reviewer.user().username(),
                        GitLabMergeRequestProcessor.SyncReviewerData::reviewState)
                .startsWith(tuple("user100", "APPROVED"))
                .endsWith(tuple("user3", null));
    }

    @Test
    void shouldHandOnNoReviewerListWhenTheFollowUpPagesRepeatACursor() {
        scriptedResponses.add(listing(reviewers(60, true, "first-end", twentyReviewersFrom(100))));
        scriptedResponses.add(reviewerPage(reviewers(60, true, "second-end", twentyReviewersFrom(200))));
        scriptedResponses.add(reviewerPage(reviewers(60, true, "second-end", twentyReviewersFrom(300))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(syncedReviewers()).isNull();
    }

    private @Nullable List<GitLabMergeRequestProcessor.SyncReviewerData> syncedReviewers() {
        ArgumentCaptor<GitLabMergeRequestProcessor.SyncMergeRequestData> synced =
                ArgumentCaptor.forClass(GitLabMergeRequestProcessor.SyncMergeRequestData.class);
        verify(mergeRequestProcessor).processFromSync(synced.capture(), any());
        return synced.getValue().syncReviewers();
    }

    private static Repository repository() {
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        return repository;
    }

    /** A reviewer node; GitLab's {@code mergeRequestInteraction} is null for one who lost access, so no state. */
    private static Map<String, @Nullable Object> reviewer(long id, String username, @Nullable String state) {
        Map<String, @Nullable Object> reviewer =
                new HashMap<>(Map.of("id", "gid://gitlab/User/" + id, "username", username));
        reviewer.put("mergeRequestInteraction", state == null ? null : Map.of("reviewState", state));
        return reviewer;
    }

    /** A full first page of {@code reviewers(first: 20)}: users {@code from}, {@code from + 1}, …, all approved. */
    private static List<Map<String, @Nullable Object>> twentyReviewersFrom(int from) {
        return IntStream.range(from, from + 20)
                .mapToObj(id -> reviewer(id, "user" + id, "APPROVED"))
                .toList();
    }

    /** A {@code reviewers} connection; {@code count} is every reviewer, on this page and the others. */
    private static Map<String, Object> reviewers(
            int count, boolean hasNextPage, String endCursor, List<Map<String, @Nullable Object>> nodes) {
        return Map.of(
                "count", count, "pageInfo", Map.of("hasNextPage", hasNextPage, "endCursor", endCursor), "nodes", nodes);
    }

    /** One page of the merge request sync, holding merge request !3 with {@code reviewers}. */
    private static ClientGraphQlResponse listing(Map<String, Object> reviewers) {
        List<Map<String, Object>> nodes = List.of(Map.of(
                "id",
                "gid://gitlab/MergeRequest/3003",
                "iid",
                "3",
                "title",
                "Add reviewers",
                "state",
                "opened",
                "reviewers",
                reviewers));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        return response(nodes);
    }

    /** One follow-up page of merge request !3's reviewers. */
    private static ClientGraphQlResponse reviewerPage(Map<String, Object> reviewers) {
        List<Map<String, Object>> nodes = List.of(Map.of("reviewers", reviewers));
        assertVendorCouldReturn(GITLAB, REVIEWER_PAGES, "project.mergeRequests.nodes", nodes);
        return response(nodes);
    }

    private static ClientGraphQlResponse response(List<Map<String, Object>> nodes) {
        ClientGraphQlResponse response = mock(ClientGraphQlResponse.class);
        lenient().when(response.isValid()).thenReturn(true);
        lenient().when(response.getErrors()).thenReturn(List.of());
        ClientResponseField nodesField = mock(ClientResponseField.class);
        lenient().doReturn(nodes).when(nodesField).toEntityList(Map.class);
        lenient().when(response.field("project.mergeRequests.nodes")).thenReturn(nodesField);
        ClientResponseField countField = mock(ClientResponseField.class);
        lenient().when(response.field("project.mergeRequests.count")).thenReturn(countField);
        ClientResponseField pageInfoField = mock(ClientResponseField.class);
        lenient().when(pageInfoField.toEntity(GitLabPageInfo.class)).thenReturn(new GitLabPageInfo(false, null));
        lenient().when(response.field("project.mergeRequests.pageInfo")).thenReturn(pageInfoField);
        return response;
    }
}
