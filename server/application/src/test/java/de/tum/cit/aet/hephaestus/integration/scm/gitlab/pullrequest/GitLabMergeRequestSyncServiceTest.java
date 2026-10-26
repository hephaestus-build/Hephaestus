package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.Vendor.GITLAB;
import static de.tum.cit.aet.hephaestus.integration.scm.GraphQlResponseStubValidator.assertVendorCouldReturn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlClientFactory;
import de.tum.cit.aet.hephaestus.integration.core.egress.SilentModeGraphQlInterceptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGraphQlConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabCredentialHealth;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials.GitLabCredentialHealthFilter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.backfill.BackfillBatchResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabAdmissionTestFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GraphQlResponses;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

class GitLabMergeRequestSyncServiceTest extends BaseUnitTest {

    private final GitLabProjectAdmission projectAdmission = mock(GitLabProjectAdmission.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(GitLabMergeRequestSyncService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

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
        appender.start();
        logger.addAppender(appender);
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
                mock(GitLabApprovalClient.class),
                projectAdmission);
        lenient().when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(client);
        lenient().when(graphQlClientProvider.getRateLimitRemaining(SCOPE_ID)).thenReturn(100);
        lenient().when(client.documentName(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.variable(anyString(), any())).thenReturn(requestSpec);
        lenient()
                .when(requestSpec.execute())
                .thenAnswer(
                        invocation -> scriptedResponses.isEmpty() ? Mono.empty() : Mono.just(scriptedResponses.poll()));
    }

    @AfterEach
    void releaseEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void shouldAbortAConcurrentWriteWithoutReadingTheNextPageAndAllowAFreshSync() {
        Repository repository = repository();
        List<Map<String, Object>> nodes = nullable(List.of(mergeRequest(3)));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes, List.of(), true));
        scriptedResponses.add(response(nodes));
        when(mergeRequestProcessor.processFromSync(any(), any()))
                .thenThrow(new PessimisticLockingFailureException(
                        "private-statement-canary", new SQLException("private-row-canary", "55P03")))
                .thenReturn(new PullRequest());

        assertThat(service.syncMergeRequests(SCOPE_ID, repository, null)).isEqualTo(SyncResult.abortedError(0));
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.syncMergeRequests(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(1));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortAConcurrentHistoricalWriteWithoutAdvancingItsCheckpointAndAllowAFreshBatch() {
        Repository repository = repository();
        List<Map<String, Object>> nodes = nullable(List.of(mergeRequest(3)));
        assertVendorCouldReturn(GITLAB, "GetProjectMergeRequestsHistorical", "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes, List.of(), true));
        scriptedResponses.add(response(nodes));
        when(mergeRequestProcessor.processFromSync(any(), any()))
                .thenThrow(new PessimisticLockingFailureException(
                        "private-statement-canary", new SQLException("private-row-canary", "55P03")))
                .thenReturn(new PullRequest());

        assertThat(service.backfillMergeRequests(SCOPE_ID, repository, null, 1))
                .isEqualTo(BackfillBatchResult.abortedWithError());
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.backfillMergeRequests(SCOPE_ID, repository, null, 1))
                .isEqualTo(new BackfillBatchResult(1, 3, 3, null, true, false));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortWithoutReadingTheNextPageAndAllowAFreshSyncWhenADiscussionWriteConflicts() {
        Repository repository = repository();
        List<Map<String, Object>> nodes = nullable(List.of(commentedMergeRequest(3)));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes, List.of(), true));
        scriptedResponses.add(response(nodes));
        when(mergeRequestProcessor.processFromSync(any(), any())).thenReturn(new PullRequest());
        when(discussionSyncService.syncDiscussionsForMergeRequest(any(), any(), anyInt(), any(), any()))
                .thenThrow(new PessimisticLockingFailureException(
                        "private-statement-canary", new SQLException("private-row-canary", "55P03")))
                .thenReturn(1);

        assertThat(service.syncMergeRequests(SCOPE_ID, repository, null)).isEqualTo(SyncResult.abortedError(0));
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.syncMergeRequests(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(1));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldAbortWithoutAdvancingTheCheckpointAndAllowAFreshBatchWhenAHistoricalDiscussionWriteConflicts() {
        Repository repository = repository();
        List<Map<String, Object>> nodes = nullable(List.of(commentedMergeRequest(3)));
        assertVendorCouldReturn(GITLAB, "GetProjectMergeRequestsHistorical", "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes, List.of(), true));
        scriptedResponses.add(response(nodes));
        when(mergeRequestProcessor.processFromSync(any(), any())).thenReturn(new PullRequest());
        when(discussionSyncService.syncDiscussionsForMergeRequest(any(), any(), anyInt(), any(), any()))
                .thenThrow(new PessimisticLockingFailureException(
                        "private-statement-canary", new SQLException("private-row-canary", "55P03")))
                .thenReturn(1);

        assertThat(service.backfillMergeRequests(SCOPE_ID, repository, null, 1))
                .isEqualTo(BackfillBatchResult.abortedWithError());
        assertThat(scriptedResponses).hasSize(1);
        assertConcurrentWriteStayedLocal();

        assertThat(service.backfillMergeRequests(SCOPE_ID, repository, null, 1))
                .isEqualTo(new BackfillBatchResult(1, 3, 3, null, true, false));
        assertThat(scriptedResponses).isEmpty();
    }

    @Test
    void shouldKeepTheMergeRequestAndReadTheNextPageWhenItsDiscussionSyncFailsForAnotherReason() {
        Repository repository = repository();
        List<Map<String, Object>> nodes = nullable(List.of(commentedMergeRequest(3)));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes, List.of(), true));
        scriptedResponses.add(response(nodes));
        when(mergeRequestProcessor.processFromSync(any(), any())).thenReturn(new PullRequest());
        when(discussionSyncService.syncDiscussionsForMergeRequest(any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("ordinary failure"))
                .thenReturn(1);

        assertThat(service.syncMergeRequests(SCOPE_ID, repository, null)).isEqualTo(SyncResult.completed(2));
        assertThat(scriptedResponses).isEmpty();
        verify(graphQlClientProvider, never()).recordFailure(any());
        assertThat(appender.list)
                .noneMatch(
                        event -> event.getFormattedMessage().equals("Mirror write conflicts with another transaction"));
    }

    /** Merge request !{@code iid} with a user note, so the sync reads its discussions. */
    private static Map<String, @Nullable Object> commentedMergeRequest(int iid) {
        Map<String, @Nullable Object> node = mergeRequest(iid);
        node.put("userNotesCount", 1);
        return node;
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
    void shouldReadDiscussionsWhenTheUserNotesCountIsUnknown() {
        assertThat(GitLabMergeRequestSyncService.readsDiscussions(null, false, "opened"))
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

    @ParameterizedTest(name = "{0}: headPipeline {1}")
    @CsvSource({
        "GetProjectMergeRequests, answered as null, NO_PIPELINE, NO_PIPELINE",
        "GetProjectMergeRequests, left out, NOT_CAPTURED, SUCCESS",
        "GetProjectMergeRequests, failed to load, NOT_CAPTURED, SUCCESS",
        "GetProjectMergeRequestsHistorical, answered as null, NO_PIPELINE, NO_PIPELINE",
        "GetProjectMergeRequestsHistorical, left out, NOT_CAPTURED, SUCCESS",
        "GetProjectMergeRequestsHistorical, failed to load, NOT_CAPTURED, SUCCESS"
    })
    void shouldReplaceTheStoredPipelineOnlyWhenTheProductionCodecsReadThatTheHeadHasNone(
            String document, String headPipeline, GitLabHeadPipeline.Kind read, CheckState stored) {
        String head = "b".repeat(40);
        Map<String, @Nullable Object> node = mergeRequest(3);
        if (!headPipeline.equals("left out")) {
            node.put("headPipeline", null);
        }
        List<Map<String, Object>> nodes = nullable(List.of(node));
        assertVendorCouldReturn(GITLAB, document, "project.mergeRequests.nodes", nodes);
        List<Map<String, ?>> errors = headPipeline.equals("failed to load")
                ? List.of(GraphQlResponses.error(
                        "Internal server error", "project", "mergeRequests", "nodes", 0, "headPipeline"))
                : List.of();
        when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(productionCodecClient(body(nodes, errors)));
        PullRequest pr = new PullRequest();
        pr.setHeadRefOid(head);
        pr.observeHeadChecks(head, CheckState.SUCCESS, true, Instant.EPOCH);

        if (document.equals(LISTING)) {
            service.syncMergeRequests(SCOPE_ID, repository(), null);
        } else {
            service.backfillMergeRequests(SCOPE_ID, repository(), null, 10);
        }

        List<GitLabMergeRequestProcessor.SyncMergeRequestData> synced = synced();
        assertThat(synced).hasSize(1);
        GitLabHeadPipeline observed = synced.getFirst().headPipeline();
        assertThat(observed.kind()).isEqualTo(read);
        observed.observeOn(pr, Instant.EPOCH.plusSeconds(60));
        assertThat(pr.getHeadCheckSha()).isEqualTo(head);
        assertThat(pr.getHeadCheckState()).isEqualTo(stored);
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

    @Test
    void shouldPreserveMissingMergeRequestCountersAsUnknown() {
        List<Map<String, Object>> nodes = nullable(List.of(mergeRequest(3)));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced()).singleElement().satisfies(data -> {
            assertThat(data.commitCount()).isNull();
            assertThat(data.commentsCount()).isNull();
            assertThat(data.discussionLocked()).isNull();
            assertThat(data.additions()).isNull();
            assertThat(data.deletions()).isNull();
            assertThat(data.fileCount()).isNull();
        });
    }

    @Test
    void shouldPreserveOnlyMissingDiffSummaryFieldsAsUnknown() {
        Map<String, @Nullable Object> node = mergeRequest(3);
        node.put("commitCount", 0);
        node.put("userNotesCount", 0);
        node.put("discussionLocked", false);
        node.put("diffStatsSummary", Map.of("additions", 0));
        List<Map<String, Object>> nodes = nullable(List.of(node));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(nodes));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced()).singleElement().satisfies(data -> {
            assertThat(data.commitCount()).isZero();
            assertThat(data.commentsCount()).isZero();
            assertThat(data.discussionLocked()).isFalse();
            assertThat(data.additions()).isZero();
            assertThat(data.deletions()).isNull();
            assertThat(data.fileCount()).isNull();
        });
    }

    @ParameterizedTest
    @CsvSource({"additions,,0,0", "deletions,0,,0", "fileCount,0,0,"})
    void shouldNotPassFailedCounterFieldsAsKnownValuesOrDiscardValidSiblings(
            String failedField, @Nullable Integer additions, @Nullable Integer deletions, @Nullable Integer fileCount) {
        Map<String, @Nullable Object> failed = countedMergeRequest(3);
        Map<String, @Nullable Object> valid = countedMergeRequest(4);
        List<Map<String, Object>> nodes = nullable(List.of(failed, valid));
        assertVendorCouldReturn(GITLAB, LISTING, "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(
                nodes,
                List.of(
                        GraphQlResponses.error("Read failed", "project", "mergeRequests", "nodes", 0, "commitCount"),
                        GraphQlResponses.error("Read failed", "project", "mergeRequests", "nodes", 0, "userNotesCount"),
                        GraphQlResponses.error(
                                "Read failed", "project", "mergeRequests", "nodes", 0, "discussionLocked"),
                        GraphQlResponses.error(
                                "Read failed",
                                "project",
                                "mergeRequests",
                                "nodes",
                                0,
                                "diffStatsSummary",
                                failedField))));

        service.syncMergeRequests(SCOPE_ID, repository(), null);

        assertThat(synced())
                .extracting(
                        GitLabMergeRequestProcessor.SyncMergeRequestData::commitCount,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::commentsCount,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::discussionLocked,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::additions,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::deletions,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::fileCount)
                .containsExactly(tuple(null, null, null, additions, deletions, fileCount), tuple(0, 0, false, 0, 0, 0));
    }

    @Test
    void shouldNotPassFailedDiffSummaryAsKnownCountsOnHistoricalPages() {
        List<Map<String, Object>> nodes = nullable(List.of(countedMergeRequest(3), countedMergeRequest(4)));
        assertVendorCouldReturn(GITLAB, "GetProjectMergeRequestsHistorical", "project.mergeRequests.nodes", nodes);
        scriptedResponses.add(response(
                nodes,
                List.of(GraphQlResponses.error(
                        "Read failed", "project", "mergeRequests", "nodes", 0, "diffStatsSummary"))));

        service.backfillMergeRequests(SCOPE_ID, repository(), null, 10);

        assertThat(synced())
                .extracting(
                        GitLabMergeRequestProcessor.SyncMergeRequestData::additions,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::deletions,
                        GitLabMergeRequestProcessor.SyncMergeRequestData::fileCount)
                .containsExactly(tuple(null, null, null), tuple(0, 0, 0));
    }

    private static Map<String, @Nullable Object> countedMergeRequest(int iid) {
        Map<String, @Nullable Object> node = mergeRequest(iid);
        node.put("commitCount", 0);
        node.put("userNotesCount", 0);
        node.put("discussionLocked", false);
        node.put("diffStatsSummary", Map.of("additions", 0, "deletions", 0, "fileCount", 0));
        return node;
    }

    private void assertConcurrentWriteStayedLocal() {
        verify(graphQlClientProvider, never()).recordFailure(any());
        assertThat(appender.list)
                .filteredOn(
                        event -> event.getFormattedMessage().equals("Mirror write conflicts with another transaction"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getKeyValuePairs()).isNotNull();
                    assertThat(event.getKeyValuePairs().stream()
                                    .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)))
                            .containsExactlyInAnyOrderEntriesOf(Map.of(
                                    "event.name",
                                    "integration.mirror.write.conflict",
                                    "error.type",
                                    PessimisticLockingFailureException.class.getName(),
                                    "integration.scope.id",
                                    SCOPE_ID));
                    assertThat(event.getArgumentArray()).isNullOrEmpty();
                });
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("canary");
            assertThat(event.getThrowableProxy()).isNull();
        });
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

    private Repository repository() {
        Repository repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(FULL_PATH);
        GitLabAdmissionTestFixtures.permit(projectAdmission, SCOPE_ID, repository, 7L);
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
        return response(nodes, errors, false);
    }

    private static ClientGraphQlResponse response(
            List<Map<String, Object>> nodes, List<Map<String, ?>> errors, boolean hasNextPage) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", hasNextPage);
        pageInfo.put("endCursor", hasNextPage ? "next-page" : null);
        return GraphQlResponses.of(
                Map.of("project", Map.of("mergeRequests", Map.of("nodes", nodes, "pageInfo", pageInfo))), errors);
    }

    /**
     * The JSON body of a page holding {@code nodes}, with {@code errors} beside the data and no further page. It keeps
     * every explicit null, whatever the codecs under test do with one.
     */
    private static String body(List<Map<String, Object>> nodes, List<Map<String, ?>> errors) {
        Map<String, @Nullable Object> pageInfo = new HashMap<>();
        pageInfo.put("hasNextPage", false);
        pageInfo.put("endCursor", null);
        Map<String, Object> body = new HashMap<>();
        body.put("data", Map.of("project", Map.of("mergeRequests", Map.of("nodes", nodes, "pageInfo", pageInfo))));
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }
        return JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .build()
                .writeValueAsString(body);
    }

    /**
     * The GitLab GraphQL client that {@link GitLabGraphQlConfig} builds, answered with {@code body}. Its mapper omits
     * null values and null map entries, as the application's mapper is configured to; this unit setup builds it here
     * and does not load the application context. A last filter returns the body in place of the connector, so nothing
     * leaves the test.
     */
    private static HttpGraphQlClient productionCodecClient(String body) {
        JsonMapper omittingNulls = JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.NON_NULL))
                .build();
        var config = new GitLabGraphQlConfig(new GitLabRateLimitTracker(new SimpleMeterRegistry()));
        // The application's builder carries the credential health filter through its customizer.
        var health = new GitLabCredentialHealthFilter(
                new StaticListableBeanFactory().getBeanProvider(GitLabCredentialHealth.class));
        var webClient =
                config.gitLabGraphQlWebClient(omittingNulls, WebClient.builder().filter(health.filter()));
        return config.gitLabGraphQlClient(
                        webClient,
                        new SilentModeGraphQlClientFactory(
                                new SilentModeGraphQlInterceptor(mock(OutboundEgressGuard.class))))
                .mutate()
                .url("https://gitlab.example.test/api/graphql")
                .webClient(builder -> builder.filter((request, next) -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build())))
                .build();
    }
}
