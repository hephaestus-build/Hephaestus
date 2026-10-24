package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RetainedFeedbackTestData;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitObservationSink;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendRecovery;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGitHub;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One item review from the worker's admission to its posted copy on the pull request. Admission, preparation, the
 * gate, the target resolver and the send state machine are the application's own beans; only GitHub's GraphQL
 * transport is scripted.
 */
class ReviewToDeliveryContractIntegrationTest extends BaseIntegrationTest {
    private static final String WORKER = "review-to-delivery-worker";
    private static final String RECORD = "context/pull-request.md";
    private static final String QUOTE = "The export handler now catches the failure and returns without its cause.";
    private static final String SUMMARY = "Keep the failure cause when the export handler gives up.";

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewAdmissionData admissions;

    @Autowired
    private RateLimitObservationSink rateLimits;

    @Autowired
    private ResultAdmissionService admission;

    @Autowired
    private JobEvidenceFiles files;

    @Autowired
    private PersonDataCopyRecorder recorder;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewItemRepository reviewItems;

    @Autowired
    private ResultRepository results;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private FeedbackBasisRepository basis;

    @Autowired
    private FeedbackCopyRepository copies;

    @Autowired
    private FeedbackSendRepository sends;

    @Autowired
    private ScmItemService itemSource;

    @Autowired
    private ItemRepository items;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private InstanceSettingsService settings;

    @Autowired
    private ScriptedGitHub github;

    @Autowired
    private FeedbackSendRecovery recovery;

    @Autowired
    private FeedbackDecisionService decisions;

    @Autowired
    private FeedbackContentDigest digests;

    @Autowired
    private CredentialBundleConverter credentials;

    private boolean silencedBefore;

    @BeforeEach
    void deliverPublicCopies() {
        // A new instance starts silenced, and so does the test database; these tests assert what is posted.
        silencedBefore = settings.get().isSilentModeEngaged();
        silence(false);
    }

    @AfterEach
    void restoreSilentMode() {
        silence(silencedBefore);
    }

    @Test
    void shouldRefuseAdmissionWithoutWritingAResultWhenTheRequestNamesAnotherAttempt() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var request = request(review);
            var otherReview = new AdmissionRequestDTO(
                    3, UUID.randomUUID(), review.identity().jobId(), 0, request.results(), List.of());
            var otherJob =
                    new AdmissionRequestDTO(3, review.reviewId(), UUID.randomUUID(), 0, request.results(), List.of());
            var otherAttempt = new AdmissionRequestDTO(
                    3, review.reviewId(), review.identity().jobId(), 1, request.results(), List.of());

            for (var mismatched : List.of(otherReview, otherJob, otherAttempt))
                assertThatThrownBy(() -> admission.admit(review.identity(), mismatched))
                        .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);

            assertThat(resultIds(review)).isEmpty();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(member -> {
                        assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED);
                        assertThat(member.getResultId()).isNull();
                    });
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            assertThat(job.getAdmissionDigest()).isNull();
            assertThat(job.getAdmissionAttempt()).isNull();
        }
    }

    @Test
    void shouldPostTheAdmittedObservationOnThePullRequestWhenTheReviewedVersionIsStillCurrent() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admission.admit(review.identity(), request(review));

            var admitted = response.admitted().getFirst();
            assertThat(response.contractVersion()).isEqualTo(AdmissionResponseDTO.CONTRACT_VERSION);
            assertThat(response.reviewId()).isEqualTo(review.reviewId());
            assertThat(response.jobId()).isEqualTo(review.identity().jobId());
            assertThat(response.attempt()).isZero();
            assertThat(response.withheld()).isEmpty();
            assertThat(response.publicReviewed()).isFalse();
            assertThat(response.privateProgress().decidedResultIds()).isEmpty();
            assertThat(response.admitted()).singleElement().satisfies(row -> {
                assertThat(row.practiceSlug()).isEqualTo(review.practiceSlug());
                assertThat(row.itemId()).isEqualTo(Long.toString(review.item().itemId()));
                assertThat(row.outcome()).isEqualTo(ResultOutcome.NOT_MET);
                assertThat(row.summary()).isEqualTo("The export handler drops the failure cause");
                assertThat(row.problems()).singleElement().satisfies(problem -> {
                    assertThat(problem.resultId()).isEqualTo(row.resultId());
                    assertThat(problem.position()).isZero();
                    assertThat(problem.key()).isEqualTo("failure-cause-dropped");
                    assertThat(problem.severity()).isEqualTo(Severity.MAJOR);
                    assertThat(problem.summary()).isEqualTo("The failure cause is dropped");
                });
            });
            assertThat(resultIds(review)).containsExactly(admitted.resultId());
            var result = results.findByIdAndWorkspaceId(admitted.resultId(), review.workspaceId())
                    .orElseThrow();
            assertThat(result.getReviewId()).isEqualTo(review.reviewId());
            assertThat(result.getItemId()).isEqualTo(review.item().itemId());
            assertThat(result.getItemVersion()).isEqualTo(review.itemVersion());
            assertThat(result.getDeveloperId()).isEqualTo(review.developerId());
            assertThat(result.getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
            assertThat(result.getSeverity()).isEqualTo(Severity.MAJOR);
            assertThat(result.getEndedAt()).isNull();
            assertThat(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(review.workspaceId(), result.getId()))
                    .singleElement()
                    .satisfies(problem -> {
                        assertThat(problem.getKey()).isEqualTo("failure-cause-dropped");
                        assertThat(problem.getSeverity()).isEqualTo(Severity.MAJOR);
                        assertThat(problem.getCitations().get(0).path("quote").asString())
                                .isEqualTo(QUOTE);
                        assertThat(problem.getCitations()
                                        .get(0)
                                        .path("verification")
                                        .path("status")
                                        .asString())
                                .isEqualTo("VERIFIED");
                    });
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(member -> {
                        assertThat(member.getState()).isEqualTo(ReviewItemState.JUDGED);
                        assertThat(member.getResultId()).isEqualTo(result.getId());
                    });
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            assertThat(job.getAdmissionAttempt()).isZero();
            assertThat(job.getAdmissionDigest()).isEqualTo(response.admissionDigest());

            var replay = admission.admit(review.identity(), request(review));
            assertThat(replay.admissionDigest()).isEqualTo(response.admissionDigest());
            assertThat(replay.admitted())
                    .extracting(AdmittedResultDTO::resultId)
                    .containsExactly(result.getId());
            assertThatThrownBy(() -> admission.admit(
                            review.identity(), request(review, "The handler keeps the cause but drops the id.")))
                    .isInstanceOf(AdmissionConflictException.class);
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getAdmissionDigest())
                    .isEqualTo(response.admissionDigest());

            var feedbackId = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            var stale = "v1:" + review.developerId() + ":" + "0".repeat(64);
            // Before the review is publicly reviewed, the composition is checked against the history it was read from.
            assertThatThrownBy(() ->
                            admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, stale)))
                    .isInstanceOfSatisfying(
                            PublicationContextRefusedException.class,
                            refused ->
                                    assertThat(refused.reason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED));
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();

            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var prepared =
                    admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));

            assertThat(prepared.feedbackIds()).containsExactly(feedbackId);
            assertThat(admission
                            .admittedResponse(
                                    jobs.findById(review.identity().jobId()).orElseThrow())
                            .publicReviewed())
                    .isTrue();
            var replayed =
                    admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));
            assertThat(replayed.feedbackIds()).isEqualTo(prepared.feedbackIds());
            assertThat(replayed.contentDigest()).isEqualTo(prepared.contentDigest());
            assertThatThrownBy(() -> admission.prepareFeedback(
                            review.identity(),
                            composition(review, response, "Different words for the same observation.", context)))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            conflict ->
                                    assertThat(conflict.getStatusCode().value()).isEqualTo(409));
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getDestination()).isEqualTo(FeedbackDestination.ON_WORK);
                        assertThat(row.getState()).isEqualTo(FeedbackState.READY);
                        assertThat(row.getItemId()).isEqualTo(review.item().itemId());
                        assertThat(row.getBody()).isEqualTo(SUMMARY);
                        assertThat(row.getDeliveredAt()).isNull();
                    });
            assertThat(basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(edge -> {
                        assertThat(edge.getResultId()).isEqualTo(result.getId());
                        assertThat(edge.getProblemId())
                                .isEqualTo(admitted.problems().getFirst().id());
                    });
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getKind()).isEqualTo(FeedbackCopyKind.SUMMARY);
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.PLANNED);
                        assertThat(copy.getExternalRef()).isNull();
                    });
            assertThat(sends.findByFeedbackIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(send -> {
                        assertThat(send.getAction()).isEqualTo(FeedbackSendAction.POST);
                        assertThat(send.getState()).isEqualTo(FeedbackSendState.PENDING);
                    });
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isEqualTo(prepared.contentDigest());

            recovery.recover();

            var posted =
                    github.threads().on(ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber()));
            assertThat(posted)
                    .singleElement()
                    .satisfies(comment -> assertThat(comment.body()).contains(SUMMARY));
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED);
                        assertThat(row.getStopReason()).isNull();
                        assertThat(row.getDeliveredAt()).isNotNull();
                    });
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.POSTED);
                        assertThat(copy.getExternalRef())
                                .isEqualTo(posted.getFirst().id());
                        assertThat(copy.getWriteStartedAt()).isNotNull();
                        assertThat(copy.getPostedAt()).isNotNull();
                    });
            assertThat(sends.findByFeedbackIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(send -> assertThat(send.getState()).isEqualTo(FeedbackSendState.DONE));
        }
    }

    @Test
    void shouldReplayTheAcceptedPackageButWithholdItsSendWhenAnotherReviewPostedOnThePullRequestSinceAcceptance() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admission.admit(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var composed = composition(review, response, SUMMARY, context);
            var prepared = admission.prepareFeedback(review.identity(), composed);
            var feedbackId = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            var sibling = RetainedFeedbackTestData.onWork(
                    jdbc, feedback, review.item(), review.developerId(), FeedbackState.DELIVERED);
            copies.saveAndFlush(RetainedFeedbackTestData.posted(
                    sibling, FeedbackCopyKind.SUMMARY, 0, "IC_sibling", "https://github.com/comment"));
            assertThat(admission.publicFeedbackHistory(review.identity()).publicationContext())
                    .isNotEqualTo(context);

            // An accepted public package replays by its digest; the history it was composed from is not read again.
            var replayed = admission.prepareFeedback(review.identity(), composed);

            assertThat(replayed.feedbackIds()).isEqualTo(prepared.feedbackIds());
            assertThat(replayed.contentDigest()).isEqualTo(prepared.contentDigest());

            recovery.recover();

            assertThat(github.threads().on(ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber())))
                    .isEmpty();
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
                        assertThat(row.getStopReason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
                    });
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.WITHHELD);
                        assertThat(copy.getWriteStartedAt()).isNull();
                    });
        }
    }

    @Test
    void shouldWithholdThePreparedObservationWithoutPostingWhenThePullRequestMovedBeforeTheSend() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admission.admit(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));
            var feedbackId = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            var pull = pullRequests.findById(review.pullRequestId()).orElseThrow();
            pull.setHeadRefOid("d".repeat(40));
            pullRequests.saveAndFlush(pull);

            recovery.recover();

            assertThat(github.threads().on(ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber())))
                    .isEmpty();
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
                        assertThat(row.getStopReason()).isEqualTo(FeedbackStopReason.REVIEWED_VERSION_CHANGED);
                        assertThat(row.getDeliveredAt()).isNull();
                    });
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.WITHHELD);
                        assertThat(copy.getWriteStartedAt()).isNull();
                        assertThat(copy.getExternalRef()).isNull();
                    });
            assertThat(sends.findByFeedbackIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(send -> assertThat(send.getState()).isEqualTo(FeedbackSendState.DONE));
        }
    }

    @Test
    void shouldPostTheObservationOnlyAfterTheDeveloperApprovesItWhenThePracticeAsksForApprovalFirst() {
        try (var review = claimedReview(FeedbackMode.APPROVE_FIRST)) {
            var response = admission.admit(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));
            var feedbackId = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            var thread = ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber());

            recovery.recover();

            assertThat(github.threads().on(thread)).isEmpty();
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> assertThat(row.getDeliveredAt()).isNull());

            decisions.decide(
                    review.workspaceId(),
                    feedbackId,
                    review.developerId(),
                    new FeedbackDecisionInput(
                            FeedbackDecisionAction.APPROVE,
                            null,
                            null,
                            digests.current(review.workspaceId(), feedbackId)));

            var posted = github.threads().on(thread);
            assertThat(posted)
                    .singleElement()
                    .satisfies(comment -> assertThat(comment.body()).contains(SUMMARY));
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .hasValueSatisfying(row -> assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED));
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), feedbackId))
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.POSTED);
                        assertThat(copy.getExternalRef())
                                .isEqualTo(posted.getFirst().id());
                    });
        }
    }

    /** A running attempt of one ITEM review of a GitHub pull request, claimed by {@link #WORKER}, with its capture. */
    private ClaimedReview claimedReview(FeedbackMode mode) {
        var scenario = data.scenario();
        var workspace = scenario.workspace();
        jdbc.update(
                "UPDATE practice SET feedback_mode=? WHERE workspace_id=? AND id=?",
                mode.name(),
                workspace.getId(),
                scenario.practice().getId());

        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        long authorNativeId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        User author = users.save(TestUserFactory.createUser(authorNativeId, "author-" + authorNativeId, provider));
        var identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(Long.toString(authorNativeId));
        identities.saveAndFlush(identity);
        long installation = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        // A token connection: the provider reads its stored token instead of minting one from GitHub.
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                null,
                new ConnectionConfig.GitHubPatConfig("delivery-contract", null, Set.of()));
        connection.setCredentials(new BearerToken("contract-token-" + installation, null), credentials);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        // GitHub has reported this scope's budget, so the provider sends no REST probe off the machine.
        rateLimits.updateFromRestRateLimit(workspace.getId(), 5000, 5000, null, Instant.now());
        var now = Instant.now();
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("work-" + installation);
        repository.setNameWithOwner("delivery-contract/work-" + installation);
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(now);
        repository.setCreatedAt(now);
        repository.setUpdatedAt(now);
        repository = repositories.saveAndFlush(repository);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(provider);
        pull.setRepository(repository);
        pull.setNumber(17);
        pull.setTitle("Retry the invoice export");
        pull.setBody(QUOTE);
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(author);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/17");
        pull.setCreatedAt(now);
        pull.setUpdatedAt(now);
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("export-retry");
        pull.setBaseRefName("main");
        pull = pullRequests.saveAndFlush(pull);
        var parent = new Item();
        parent.setWorkspaceId(workspace.getId());
        parent.setKind(ScmItemEvents.REPOSITORY);
        parent.setSourceId(repository.getId());
        parent.setCreatedAt(now);
        parent = items.saveAndFlush(parent);
        var pullItem = new Item();
        pullItem.setWorkspaceId(workspace.getId());
        pullItem.setKind(ScmItemEvents.PULL_REQUEST);
        pullItem.setParentId(parent.getId());
        pullItem.setSourceId(pull.getId());
        pullItem.setCreatedAt(now);
        pullItem = items.saveAndFlush(pullItem);
        var item = new ItemRef(
                workspace.getId(),
                pullItem.getId(),
                pullItem.getKind(),
                pullItem.getSourceId(),
                pullItem.getParentId(),
                null);
        var version = itemSource.currentVersion(item).orElseThrow();

        var reviewed = new ReviewIntegrationData.Scenario(workspace, scenario.account(), scenario.practice(), pullItem);
        long developerId = Objects.requireNonNull(scenario.account().getId());
        var pending = admissions.pending(reviewed, now, developerId, version.value());
        jdbc.update(
                "UPDATE review SET base_version=? WHERE workspace_id=? AND id=?",
                version.baseVersion(),
                workspace.getId(),
                pending.review().getId());
        jdbc.update(
                "UPDATE review_job SET worker_id=? WHERE workspace_id=? AND id=?",
                WORKER,
                workspace.getId(),
                pending.jobId());

        var job = jobs.findById(pending.jobId()).orElseThrow();
        byte[] record = ("# Retry the invoice export\n\n" + QUOTE + "\n").getBytes(StandardCharsets.UTF_8);
        files.beginPersonCapture(job);
        recorder.recordIdentity(
                new PersonCopyIdentity("GITHUB", provider.getServerUrl(), Long.toString(authorNativeId), null));
        var inputs = files.prepare(job, new PreparedEvidence(Map.of(RECORD, record), null), null);
        job.setCapture(capture(ProvenanceDigest.sha256Hex(record)));
        jobs.saveAndFlush(job);
        return new ClaimedReview(
                new ResultAdmissionService.AdmissionIdentity(job.getId(), workspace.getId(), 0, WORKER),
                pending.review().getId(),
                scenario.practice().getId(),
                scenario.practice().getSlug(),
                item,
                version.value(),
                developerId,
                pull.getId(),
                repository.getNameWithOwner(),
                pull.getNumber(),
                inputs);
    }

    /** What the worker's capture recorded: the pull request record, staged and citable as one text. */
    private ObjectNode capture(String sha256) {
        var capture = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(capture, "scm.pull-request.core", null);
        EvidenceSnapshotFixtures.file(capture, source, RECORD, sha256);
        capture.withObject("manifest")
                .withArray("citableTexts")
                .addObject()
                .put("source", "scm.pull-request.core")
                .put("path", RECORD)
                .put("side", "NEW")
                .putNull("commit")
                .put("stagedPath", RECORD)
                .put("sha256", sha256);
        return capture;
    }

    private AdmissionRequestDTO request(ClaimedReview review) {
        return request(review, "The export handler drops the failure cause");
    }

    private AdmissionRequestDTO request(ClaimedReview review, String summary) {
        var candidate = mapper.readTree("""
            {"outcome":"NOT_MET","summary":"%s",
             "explanation":"The pull request says its new export handler returns without the failure cause, so an operator cannot trace a failed export.",
             "citations":[],
             "problems":[{"key":"failure-cause-dropped","severity":"MAJOR","summary":"The failure cause is dropped",
               "explanation":"The new handler catches the export failure and returns without it.",
               "citations":[{"sourceKind":"scm.pull-request.core","sourcePath":"%s","path":"%s",
                 "startLine":3,"endLine":3,"quote":"%s"}],
               "basis":{"type":"introduced","head":0,"explanation":"This pull request adds the handler."},
               "nextStep":"Rethrow the failure with its cause, or record the export as failed."}],
             "evidence":{},
             "coverage":{"source":"claimed","selected":1,"checked":1,"total":1,"complete":true}}
            """.formatted(summary, RECORD, RECORD, QUOTE));
        return new AdmissionRequestDTO(
                3,
                review.reviewId(),
                review.identity().jobId(),
                review.identity().attempt(),
                List.of(new SubmittedResultDTO(
                        review.practiceSlug(),
                        Long.toString(review.item().itemId()),
                        review.itemVersion(),
                        candidate,
                        List.of())),
                List.of());
    }

    /** One summary on the work, composed from the public history named by {@code publicationContext}. */
    private static PrepareFeedbackRequestDTO composition(
            ClaimedReview review, AdmissionResponseDTO admitted, String summary, @Nullable String publicationContext) {
        var result = admitted.admitted().getFirst();
        var problem = result.problems().getFirst().id();
        var support = List.of(new FeedbackCompositionTrace.Support(result.resultId(), problem));
        return new PrepareFeedbackRequestDTO(
                review.reviewId(),
                review.identity().jobId(),
                review.identity().attempt(),
                List.of(result.resultId()),
                List.of(new ComposedFeedback(
                        FeedbackDestination.ON_WORK,
                        List.of(result.resultId()),
                        summary,
                        List.of(),
                        null,
                        new FeedbackCompositionTrace(
                                List.of(new FeedbackCompositionTrace.CopySupport(FeedbackCopyKind.SUMMARY, 0, support)),
                                List.of(),
                                null,
                                result.resultId()),
                        null)),
                true,
                publicationContext);
    }

    private List<UUID> resultIds(ClaimedReview review) {
        return results
                .findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(review.workspaceId(), review.reviewId())
                .stream()
                .map(Result::getId)
                .toList();
    }

    private void silence(boolean engaged) {
        var current = settings.get();
        if (current.isSilentModeEngaged() != engaged)
            settings.updateSilentMode(
                    engaged,
                    engaged ? "restored after the delivery contract test" : null,
                    null,
                    EntityTagPrecondition.parse("\"" + current.getVersion() + "\""));
    }

    private record ClaimedReview(
            ResultAdmissionService.AdmissionIdentity identity,
            UUID reviewId,
            long practiceId,
            String practiceSlug,
            ItemRef item,
            String itemVersion,
            long developerId,
            long pullRequestId,
            String repository,
            int pullRequestNumber,
            PreparedJobInputs inputs)
            implements AutoCloseable {
        long workspaceId() {
            return identity.workspaceId();
        }

        @Override
        public void close() {
            inputs.close();
        }
    }
}
