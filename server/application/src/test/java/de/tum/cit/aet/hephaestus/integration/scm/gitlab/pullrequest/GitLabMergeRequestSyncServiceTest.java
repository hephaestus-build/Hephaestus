package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
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
import org.springframework.graphql.client.GraphQlClient;
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
    private GraphQlClient.RequestSpec requestSpec;

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
                        Duration.ofMinutes(5)),
                mock(GitLabApprovalClient.class));
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

    @Test
    void shouldTellAHeadPipelineThatFailedToLoadFromOneGitLabSaysIsNotThere() {
        Map<String, @Nullable Object> failed = mergeRequest(3);
        failed.put("headPipeline", null);
        Map<String, @Nullable Object> none = mergeRequest(4);
        none.put("headPipeline", null);
        Map<String, @Nullable Object> skipped = mergeRequest(5);
        skipped.put("headPipeline", Map.of("status", "SKIPPED", "sha", "c".repeat(40)));
        List<Map<String, Object>> nodes = nullable(List.of(failed, none, skipped));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(
                nodes,
                List.of(GraphQlResponses.error(
                        "Internal server error", "project", "mergeRequests", "nodes", 0, "headPipeline"))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced())
                .extracting(GitLabMergeRequestProcessor.SyncMergeRequestData::headPipeline)
                .containsExactly(
                        GitLabHeadPipeline.NOT_CAPTURED,
                        GitLabHeadPipeline.NO_PIPELINE,
                        GitLabHeadPipeline.reported("SKIPPED", "c".repeat(40)));
    }

    @Test
    void shouldTreatAPipelineWhoseStatusFailedToLoadAsNotCaptured() {
        Map<String, @Nullable Object> node = mergeRequest(3);
        Map<String, @Nullable Object> pipeline = new HashMap<>();
        pipeline.put("status", null);
        pipeline.put("sha", "c".repeat(40));
        node.put("headPipeline", pipeline);
        List<Map<String, Object>> nodes = nullable(List.of(node));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(
                nodes,
                List.of(GraphQlResponses.error(
                        "Internal server error", "project", "mergeRequests", "nodes", 0, "headPipeline", "status"))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced())
                .singleElement()
                .satisfies(data -> assertThat(data.headPipeline()).isEqualTo(GitLabHeadPipeline.NOT_CAPTURED));
    }

    @Test
    void shouldHandOnNoApproversAndNoApprovalWhereThoseFieldsFailedToLoad() {
        Map<String, @Nullable Object> node = mergeRequest(3);
        node.put("approved", null);
        node.put("approvedBy", null);
        List<Map<String, Object>> nodes = nullable(List.of(node));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(
                nodes,
                List.of(
                        GraphQlResponses.error(
                                "Internal server error", "project", "mergeRequests", "nodes", 0, "approved"),
                        GraphQlResponses.error(
                                "Internal server error", "project", "mergeRequests", "nodes", 0, "approvedBy"))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced()).singleElement().satisfies(data -> {
            assertThat(data.approved()).isNull();
            assertThat(data.syncApprovers()).isNull();
        });
    }

    @Test
    void shouldHandOnADiffBaseOnlyBesideTheHeadGitLabPairedItWith() {
        String head = "b".repeat(40);
        String base = "a".repeat(40);
        Map<String, @Nullable Object> paired = mergeRequest(3);
        paired.put("diffRefs", Map.of("headSha", head, "baseSha", base));
        Map<String, @Nullable Object> otherHead = mergeRequest(4);
        otherHead.put("diffRefs", Map.of("headSha", "c".repeat(40), "baseSha", base));
        Map<String, @Nullable Object> noPairedHead = mergeRequest(5);
        noPairedHead.put("diffRefs", Map.of("baseSha", base));
        Map<String, @Nullable Object> blankPairedHead = mergeRequest(6);
        blankPairedHead.put("diffRefs", Map.of("headSha", " ", "baseSha", base));
        Map<String, @Nullable Object> blankBase = mergeRequest(7);
        blankBase.put("diffRefs", Map.of("headSha", head, "baseSha", " "));
        Map<String, @Nullable Object> noBase = mergeRequest(8);
        noBase.put("diffRefs", Map.of("headSha", head));
        Map<String, @Nullable Object> noRefs = mergeRequest(9);
        noRefs.put("diffRefs", null);
        Map<String, @Nullable Object> noHead = mergeRequest(10);
        noHead.put("diffHeadSha", null);
        noHead.put("diffRefs", Map.of("headSha", head, "baseSha", base));
        List<Map<String, Object>> nodes =
                nullable(List.of(paired, otherHead, noPairedHead, blankPairedHead, blankBase, noBase, noRefs, noHead));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced())
                .extracting(GitLabMergeRequestProcessor.SyncMergeRequestData::baseSha)
                .containsExactly(base, null, null, null, null, null, null, null);
    }

    @Test
    void shouldHandOnNoDiffBaseWhereItsHeadOrPairFailedToLoadAndLeaveTheOtherMergeRequestsAlone() {
        String head = "b".repeat(40);
        String base = "a".repeat(40);
        List<Map<String, @Nullable Object>> read = IntStream.rangeClosed(3, 7)
                .mapToObj(iid -> {
                    Map<String, @Nullable Object> node = mergeRequest(iid);
                    node.put("diffRefs", Map.of("headSha", head, "baseSha", base));
                    return node;
                })
                .toList();
        List<Map<String, Object>> nodes = nullable(read);
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        // GitLab names each failed field by its merge request's index; the last merge request has none.
        scriptedResponses.add(response(
                nodes,
                List.of(
                        GraphQlResponses.error(
                                "Internal server error", "project", "mergeRequests", "nodes", 0, "diffHeadSha"),
                        GraphQlResponses.error(
                                "Internal server error", "project", "mergeRequests", "nodes", 1, "diffRefs"),
                        GraphQlResponses.error(
                                "Internal server error", "project", "mergeRequests", "nodes", 2, "diffRefs", "headSha"),
                        GraphQlResponses.error(
                                "Internal server error",
                                "project",
                                "mergeRequests",
                                "nodes",
                                3,
                                "diffRefs",
                                "baseSha"))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced())
                .extracting(
                        GitLabMergeRequestProcessor.SyncMergeRequestData::diffHeadSha,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::baseSha)
                .containsExactly(
                        tuple(null, null), tuple(head, null), tuple(head, null), tuple(head, null), tuple(head, base));
    }

    @Test
    void shouldHandOnTheHistoricalPageDiffBaseOnlyBesideItsPairedHead() {
        String base = "a".repeat(40);
        Map<String, @Nullable Object> paired = mergeRequest(3);
        paired.put("diffRefs", Map.of("headSha", "b".repeat(40), "baseSha", base));
        Map<String, @Nullable Object> otherHead = mergeRequest(4);
        otherHead.put("diffRefs", Map.of("headSha", "c".repeat(40), "baseSha", base));
        List<Map<String, Object>> nodes = nullable(List.of(paired, otherHead));
        assertVendorCouldReturn(GITLAB, "GetProjectMergeRequestsHistorical", "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes));

        service.backfillMergeRequests(SCOPE_ID, repository(), null, 10);

        assertThat(synced())
                .extracting(GitLabMergeRequestProcessor.SyncMergeRequestData::baseSha)
                .containsExactly(base, null);
    }

    /** Merge request !{@code iid} as the listing names it, with fields a test adds. */
    private static Map<String, @Nullable Object> mergeRequest(int iid) {
        Map<String, @Nullable Object> node = new HashMap<>();
        node.put("id", "gid://gitlab/MergeRequest/300" + iid);
        node.put("iid", String.valueOf(iid));
        node.put("title", "MR !" + iid);
        node.put("state", "opened");
        node.put("diffHeadSha", "b".repeat(40));
        return node;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nullable(List<Map<String, @Nullable Object>> nodes) {
        return (List<Map<String, Object>>) (List<?>) nodes;
    }

    private List<GitLabMergeRequestProcessor.SyncMergeRequestData> synced() {
        ArgumentCaptor<GitLabMergeRequestProcessor.SyncMergeRequestData> synced =
                ArgumentCaptor.forClass(GitLabMergeRequestProcessor.SyncMergeRequestData.class);
        verify(mergeRequestProcessor, atLeastOnce()).processFromSync(synced.capture(), any());
        return synced.getAllValues();
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
        return response(nodes, List.of());
    }

    /** A page holding {@code nodes} and no further page, with {@code errors} beside the data. */
    private static ClientGraphQlResponse response(List<Map<String, Object>> nodes, List<Map<String, ?>> errors) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", false);
        pageInfo.put("endCursor", null);
        return GraphQlResponses.of(
                Map.of("project", Map.of("mergeRequests", Map.of("nodes", nodes, "pageInfo", pageInfo))), errors);
    }
}
