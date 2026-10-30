package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionCapacity;
import de.tum.cit.aet.hephaestus.agent.composer.WorkerFeedbackComposer;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.AdmissionConflictException;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.PublicationContextRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RetainedFeedbackTestData;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasis;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRecovery;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
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
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendRecovery;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
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
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGitHub;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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
    private PracticeVersionService versions;

    @Autowired
    private ResultAdmissionService admission;

    @Autowired
    private JobEvidenceFiles files;

    @Autowired
    private PersonDataCopyRecorder recorder;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewCompositionContinuation continuation;

    @Autowired
    private ReviewJobAssignments assignments;

    @Autowired
    private ItemDescriptorRegistry descriptors;

    @Autowired
    private PracticeReviewProperties reviewProperties;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TransactionTemplate transactions;

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

    @Autowired
    private PracticeStagingStore stagingStore;

    @Autowired
    private ReviewJobRecovery attemptRecovery;

    @Autowired
    private ReviewJobCompletionService completion;

    @Autowired
    private ReviewCompositionBasisRepository compositionBases;

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
    void shouldKeepStagingPrivateAndSealAfterAnInterruptedAttemptWithoutItsFolder() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var request = request(review);
            var receipt = admission.stage(review.identity(), request);
            assertThat(admission.stage(review.identity(), request)).isEqualTo(receipt);
            assertThat(resultIds(review)).isEmpty();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(member -> {
                        assertThat(member.getState()).isEqualTo(ReviewItemState.STAGED);
                        assertThat(member.getResultId()).isNull();
                    });
            assertThatThrownBy(() -> admission.stage(review.identity(), request(review, "A changed candidate")))
                    .isInstanceOf(AdmissionConflictException.class);
            var before = jobs.findById(review.identity().jobId()).orElseThrow();
            var directory = files.renderingDirectory(before);
            review.inputs().close();
            FileUtils.deleteDirectory(directory.toFile());
            before.setAttempt(1);
            before.setWorkerId("replacement-worker");
            before.setLeasedUntil(Instant.now().plusSeconds(3600));
            jobs.saveAndFlush(before);
            var identity = new ResultAdmissionService.AdmissionIdentity(
                    before.getId(), review.workspaceId(), 1, "replacement-worker");
            assertThatThrownBy(() -> admission.stage(review.identity(), request))
                    .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
            assertThat(admission.sealIfComplete(identity)).isTrue();
            assertThat(admission.sealIfComplete(identity)).isFalse();
            var sealed = jobs.findById(before.getId()).orElseThrow();
            assertThat(resultIds(review)).hasSize(1);
            assertThat(sealed.getAdmissionAttempt()).isEqualTo(1);
            assertThat(continuation.retainedInputs(sealed)).anySatisfy((path, bytes) -> {
                assertThat(path).startsWith("context/staged/");
                assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(QUOTE);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepProvenInapplicabilityWhenSealingRetainedWork(boolean interrupted) {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var scenario = review.pending().scenario();
            var sibling = versions.create(new PracticeVersionService.NewPractice(
                    scenario.workspace(),
                    "absent-sibling",
                    scenario.item().getKind(),
                    DefinitionTestFixtures.definition(),
                    null,
                    1,
                    review.developerId()));
            admissions.assigned(review.pending(), sibling);
            admission.stage(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            ((ObjectNode) Objects.requireNonNull(job.getCapture()))
                    .withObject("readiness")
                    .withObject("applicability")
                    .set(
                            sibling.getSlug(),
                            mapper.valueToTree(new AppliesWhenCheck(
                                    true,
                                    "The occasion does not apply",
                                    List.of(new AppliesWhenCheck.Clause(
                                            "An applicable occasion",
                                            new SourceKind("scm.pull-request.core"),
                                            AppliesWhenCheck.Result.NOT_FOUND)))));
            jobs.saveAndFlush(job);

            assertThat(assignments.readyToSeal(job)).isTrue();
            if (interrupted) admission.finishInterrupted(review.identity(), ReviewStopReason.EXECUTION_INCOMPLETE);
            else assertThat(admission.sealIfComplete(review.identity())).isTrue();

            var member = reviewItems
                    .findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            sibling.getId(),
                            review.item().itemId())
                    .orElseThrow();
            assertThat(member.getState()).isEqualTo(ReviewItemState.JUDGED);
            assertThat(member.getReason()).isNull();
            assertThat(results.findById(Objects.requireNonNull(member.getResultId()))
                            .orElseThrow()
                            .getOutcome())
                    .isEqualTo(ResultOutcome.DOES_NOT_APPLY);
            assertThat(resultIds(review)).hasSize(2);
        }
    }

    @Test
    void shouldSealTheValidPracticeWhenItsSiblingEvidenceExpired() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var scenario = review.pending().scenario();
            var sibling = versions.create(new PracticeVersionService.NewPractice(
                    scenario.workspace(),
                    "retained-sibling",
                    scenario.item().getKind(),
                    DefinitionTestFixtures.definition(),
                    null,
                    1,
                    review.developerId()));
            admissions.assigned(review.pending(), sibling);
            var original = request(review);
            var sent = original.results().getFirst();
            admission.stage(review.identity(), original);
            var firstBasis = compositionBases
                    .findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId())
                    .orElseThrow();
            assertThat(firstBasis.getAdmissionDigest()).isNull();
            assertThatThrownBy(() -> continuation.retainedInputs(
                            jobs.findById(review.identity().jobId()).orElseThrow()))
                    .isInstanceOf(ReviewCompositionContinuation.InputsUnavailableException.class);
            admission.stage(
                    review.identity(),
                    new AdmissionRequestDTO(
                            3,
                            review.reviewId(),
                            review.identity().jobId(),
                            0,
                            List.of(new SubmittedResultDTO(
                                    sibling.getSlug(),
                                    sent.itemId(),
                                    sent.itemVersion(),
                                    sent.candidate(),
                                    sent.readReceipts())),
                            List.of()));
            assertThat(compositionBases
                            .findByWorkspaceIdAndJobId(
                                    review.workspaceId(), review.identity().jobId())
                            .orElseThrow()
                            .getBasisDigest())
                    .isEqualTo(firstBasis.getBasisDigest());
            jdbc.update(
                    "UPDATE review_item SET staged_payload=jsonb_set(staged_payload,'{evidence,expiresAt}',to_jsonb(CAST(? AS text)))"
                            + " WHERE workspace_id=? AND review_id=? AND practice_id=?",
                    Instant.now().minusSeconds(1).toString(),
                    review.workspaceId(),
                    review.reviewId(),
                    review.practiceId());
            var sealed = admission.admit(
                    review.identity(),
                    new SealResultsRequestDTO(
                            3, review.reviewId(), review.identity().jobId(), 0));
            assertThat(sealed.admitted())
                    .singleElement()
                    .satisfies(value -> assertThat(value.practiceSlug()).isEqualTo(sibling.getSlug()));
            assertThat(sealed.withheld())
                    .singleElement()
                    .satisfies(value -> assertThat(value.reason()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE"));
            assertThat(resultIds(review)).hasSize(1);
        }
    }

    @Test
    void shouldWithholdARevokedCopyAndNeverRestoreItsCompositionInputs() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var retained = stagingStore
                    .staged(
                            review.workspaceId(),
                            review.reviewId(),
                            review.identity().jobId())
                    .getFirst();
            jdbc.update(
                    "UPDATE person_evidence_copy SET state='ERASED',payload='{}'::jsonb WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    UUID.fromString(retained.evidence().path("copyId").asString()));
            assertThat(admission.sealIfComplete(review.identity())).isTrue();
            assertThat(resultIds(review)).isEmpty();
            assertThat(stagingStore.staged(
                            review.workspaceId(),
                            review.reviewId(),
                            review.identity().jobId()))
                    .singleElement()
                    .satisfies(value -> {
                        assertThat(value.candidate()).isNull();
                        assertThat(value.reason()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
                        assertThat(value.evidence().isEmpty()).isTrue();
                    });
            assertThat(compositionBases.findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId()))
                    .isEmpty();
        }
    }

    @Test
    void shouldClearStagingWithItsCancellationCauseAtTerminalSettlement() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            jdbc.update(
                    "UPDATE review_job SET status='CANCELLED',started_at=NULL,completed_at=now(),cancellation_reason='CANCELLED_BY_REQUESTER' WHERE id=?",
                    review.identity().jobId());
            await().untilAsserted(() -> {
                completion.tick();
                assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                                review.workspaceId(),
                                review.reviewId(),
                                review.practiceId(),
                                review.item().itemId()))
                        .hasValueSatisfying(value -> {
                            assertThat(value.getState()).isEqualTo(ReviewItemState.WITHHELD);
                            assertThat(value.getReason()).isEqualTo("CANCELLED");
                            assertThat(value.getStagedAttempt()).isNull();
                        });
            });
            assertThat(jdbc.queryForObject(
                            "SELECT staged_payload IS NULL FROM review_item WHERE workspace_id=? AND review_id=? AND practice_id=? AND item_id=?",
                            Boolean.class,
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .isTrue();
            assertThat(resultIds(review)).isEmpty();
        }
    }

    @Test
    void shouldDiscardExpiredCitationsWhileRunningAndKeepItsRefusalForTheSeal() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            stagingStore.discardUnusable(
                    Instant.now().plus(ReviewCompositionBasis.RETENTION).plusSeconds(1), 1000);
            assertThat(stagingStore.staged(
                            review.workspaceId(),
                            review.reviewId(),
                            review.identity().jobId()))
                    .singleElement()
                    .satisfies(value -> {
                        assertThat(value.candidate()).isNull();
                        assertThat(value.evidence().isEmpty()).isTrue();
                        assertThat(value.reason()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE");
                    });
            var sealed = admission.admit(
                    review.identity(),
                    new SealResultsRequestDTO(
                            3, review.reviewId(), review.identity().jobId(), 0));
            assertThat(sealed.admitted()).isEmpty();
            assertThat(sealed.withheld())
                    .singleElement()
                    .satisfies(value -> assertThat(value.reason()).isEqualTo("COPY_AUTHORITY_UNAVAILABLE"));
        }
    }

    @Test
    void shouldBoundTheFinalStagedHandoffAndKeepItsMeasuredUsage() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            job.setAttempt(agentProperties.maxRetries());
            job.setExecutionStartedAt(Instant.now());
            job.setLlmCalls(1);
            jobs.saveAndFlush(job);
            transactions.executeWithoutResult(
                    status -> attemptRecovery.recover(job.getId(), WORKER, job.getAttempt(), null));
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            var last = jobs.findById(job.getId()).orElseThrow();
            last.setStatus(ReviewJobStatus.RUNNING);
            last.setWorkerId(WORKER);
            last.setLeasedUntil(Instant.now().plusSeconds(3600));
            jobs.saveAndFlush(last);
            transactions.executeWithoutResult(
                    status -> attemptRecovery.recover(last.getId(), WORKER, last.getAttempt(), null));
            assertThat(jobs.findById(last.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.FAILED);
            assertThat(jobs.findById(job.getId()).orElseThrow().getAttempt())
                    .isEqualTo(agentProperties.maxRetries() + 1);
            assertThat(jdbc.queryForObject(
                            "SELECT proxy_calls FROM review_job_attempt_usage WHERE workspace_id=? AND job_id=? AND attempt=?",
                            Integer.class,
                            review.workspaceId(),
                            job.getId(),
                            agentProperties.maxRetries()))
                    .isOne();
            completion.tick();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(value -> {
                        assertThat(value.getState()).isEqualTo(ReviewItemState.WITHHELD);
                        assertThat(value.getReason()).isEqualTo("PREPARATION_FAILED");
                    });
        }
    }

    @Test
    void shouldRecheckTheDevelopersChoiceBetweenStagingAndSealing() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            jdbc.update("UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?", review.developerId());
            assertThat(admission.sealIfComplete(review.identity())).isTrue();
            assertThat(resultIds(review)).isEmpty();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(value -> {
                        assertThat(value.getState()).isEqualTo(ReviewItemState.WITHHELD);
                        assertThat(value.getReason()).isIn("ROLE_UNAVAILABLE", "COPY_AUTHORITY_UNAVAILABLE");
                    });
        }
    }

    @Test
    void shouldEndAStagedObservationWhenTheWorkMovesBeforeTheSeal() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var pull = pullRequests.findById(review.pullRequestId()).orElseThrow();
            pull.setHeadRefOid("d".repeat(40));
            pullRequests.saveAndFlush(pull);
            assertThat(admission.sealIfComplete(review.identity())).isTrue();
            assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .singleElement()
                    .satisfies(value -> {
                        assertThat(value.getEndedAt()).isNotNull();
                        assertThat(value.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
                    });
        }
    }

    @Test
    void shouldNotRecreateAnErasedMembershipDuringTheSeal() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            jdbc.update(
                    "DELETE FROM review_item WHERE workspace_id=? AND review_id=? AND practice_id=? AND item_id=?",
                    review.workspaceId(),
                    review.reviewId(),
                    review.practiceId(),
                    review.item().itemId());
            assertThatThrownBy(() -> admission.sealIfComplete(review.identity()))
                    .isInstanceOf(JobPreparationException.class);
            assertThat(resultIds(review)).isEmpty();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .isEmpty();
        }
    }

    @Test
    void shouldRefuseLateStagingAndSealingAfterTheAttemptLeaseExpires() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            job.setLeasedUntil(Instant.now().minusSeconds(1));
            jobs.saveAndFlush(job);
            assertThatThrownBy(() -> admission.stage(review.identity(), request(review)))
                    .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
            assertThatThrownBy(() -> admission.sealIfComplete(review.identity()))
                    .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
            assertThat(resultIds(review)).isEmpty();
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), job.getId()))
                    .isEmpty();
        }
    }

    @Test
    void shouldKeepCompletedStagingAndExactMeasuredSpendWhenTheWorkerIsLost() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            job.setExecutionStartedAt(Instant.now());
            job.setLlmCalls(3);
            job.setLlmInputTokens(13);
            job.setLlmOutputTokens(7);
            job.setLlmCacheReadTokens(1200);
            job.setLlmCacheWriteTokens(300);
            jobs.saveAndFlush(job);
            transactions.executeWithoutResult(status -> attemptRecovery.recover(job.getId(), WORKER, 0, null));
            var retry = jobs.findById(job.getId()).orElseThrow();
            assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(retry.getAttempt()).isEqualTo(1);
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), job.getId()))
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());
            assertThat(jdbc.queryForMap(
                            "SELECT proxy_calls,proxy_input_tokens,proxy_output_tokens,"
                                    + "proxy_cache_read_tokens,proxy_cache_write_tokens FROM review_job_attempt_usage"
                                    + " WHERE workspace_id=? AND job_id=? AND attempt=0",
                            review.workspaceId(),
                            job.getId()))
                    .containsEntry("proxy_calls", 3)
                    .containsEntry("proxy_input_tokens", 13L)
                    .containsEntry("proxy_output_tokens", 7L)
                    .containsEntry("proxy_cache_read_tokens", 1200L)
                    .containsEntry("proxy_cache_write_tokens", 300L);
            assertThat(resultIds(review)).isEmpty();
        }
    }

    @Test
    void shouldRejectASealBeforeEveryPracticeHasEndedAndRejectAnotherWorkspace() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            assertThatThrownBy(() -> admission.admit(
                            review.identity(),
                            new SealResultsRequestDTO(
                                    3, review.reviewId(), review.identity().jobId(), 0)))
                    .isInstanceOf(AdmissionConflictException.class);
            assertThatThrownBy(() -> admission.stage(
                            new ResultAdmissionService.AdmissionIdentity(
                                    review.identity().jobId(), review.workspaceId() + 1000000, 0, WORKER),
                            request(review)))
                    .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
            assertThat(stagingStore.staged(
                            review.workspaceId() + 1000000,
                            review.reviewId(),
                            review.identity().jobId()))
                    .isEmpty();
            assertThat(resultIds(review)).isEmpty();
        }
    }

    @Test
    void shouldWriteOneResultSetWhenTwoSealRequestsRace() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var request = new SealResultsRequestDTO(
                    3, review.reviewId(), review.identity().jobId(), 0);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(() -> admission.admit(review.identity(), request));
                var second = workers.submit(() -> admission.admit(review.identity(), request));
                var left = first.get(30, TimeUnit.SECONDS);
                var right = second.get(30, TimeUnit.SECONDS);
                assertThat(left.admitted())
                        .extracting(AdmittedResultDTO::resultId)
                        .containsExactlyElementsOf(right.admitted().stream()
                                .map(AdmittedResultDTO::resultId)
                                .toList());
            }
            assertThat(resultIds(review)).hasSize(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM problem WHERE workspace_id=? AND result_id IN"
                                    + " (SELECT id FROM result WHERE workspace_id=? AND review_id=?)",
                            Long.class,
                            review.workspaceId(),
                            review.workspaceId(),
                            review.reviewId()))
                    .isEqualTo(1L);
        }
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
                assertThatThrownBy(() -> admitStaged(review.identity(), mismatched))
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
    void shouldWithholdTheResultWithoutWritingItWhenItSaysTheRepositoryLacksSomethingWithoutACheckoutToSearch() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            // The work's checkout could not be captured, so no search can show what the repository lacks.
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            var capture = (ObjectNode) Objects.requireNonNull(job.getCapture());
            EvidenceSnapshotFixtures.unavailable(
                    capture, EvidenceSnapshotFixtures.availableSource(capture, "scm.repository.tree", null));
            job.setCapture(capture);
            jobs.saveAndFlush(job);

            var response = admitStaged(
                    review.identity(),
                    request(
                            review,
                            "The export handler drops the failure cause, and nothing else in the code base"
                                    + " handles it"));

            assertThat(response.admitted()).isEmpty();
            assertThat(response.withheld()).singleElement().satisfies(withheld -> {
                assertThat(withheld.practiceSlug()).isEqualTo(review.practiceSlug());
                assertThat(withheld.reason()).isEqualTo("source_unavailable");
            });
            assertThat(resultIds(review)).isEmpty();
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(member -> {
                        assertThat(member.getState()).isEqualTo(ReviewItemState.WITHHELD);
                        assertThat(member.getReason()).isEqualTo("source_unavailable");
                        assertThat(member.getResultId()).isNull();
                    });
        }
    }

    @Test
    void shouldWithholdAnUndeclaredSourceAndComposeOnlyTheValidSibling() {
        var d = DefinitionTestFixtures.definition();
        var definition = new PracticeDefinition(
                d.name(),
                d.reviewOn(),
                d.appliesWhen(),
                d.about(),
                new PracticeReads(
                        "3.0.0",
                        List.of(new PracticeReadSource(new SourceKind("scm.pull-request.diff"), ReadNeed.REQUIRED))),
                d.reviewedBy(),
                d.criteria(),
                d.script(),
                d.scan(),
                d.lineNotes(),
                d.guidance());
        try (var review = claimedReview(FeedbackMode.AUTOMATIC, definition)) {
            var scenario = review.pending().scenario();
            var sibling = versions.create(new PracticeVersionService.NewPractice(
                    scenario.workspace(),
                    "declared-sibling",
                    scenario.item().getKind(),
                    d,
                    null,
                    1,
                    review.developerId()));
            jdbc.update(
                    "UPDATE practice SET feedback_mode='AUTOMATIC' WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    sibling.getId());
            admissions.assigned(review.pending(), sibling);
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            var original = request(review);
            var sent = original.results().getFirst();
            var request = new AdmissionRequestDTO(
                    3,
                    review.reviewId(),
                    job.getId(),
                    0,
                    List.of(
                            sent,
                            new SubmittedResultDTO(
                                    sibling.getSlug(),
                                    sent.itemId(),
                                    sent.itemVersion(),
                                    sent.candidate(),
                                    sent.readReceipts())),
                    List.of());

            var response = admitStaged(review.identity(), request);

            assertThat(response.withheld()).singleElement().satisfies(row -> {
                assertThat(row.practiceSlug()).isEqualTo(review.practiceSlug());
                assertThat(row.reason()).isEqualTo("undeclared_source_cited");
            });
            assertThat(response.admitted())
                    .singleElement()
                    .satisfies(row -> assertThat(row.practiceSlug()).isEqualTo(sibling.getSlug()));
            assertThat(reviewItems.findByWorkspaceIdAndReviewIdAndPracticeIdAndItemId(
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId(),
                            review.item().itemId()))
                    .hasValueSatisfying(row -> {
                        assertThat(row.getState()).isEqualTo(ReviewItemState.WITHHELD);
                        assertThat(row.getReason()).isEqualTo("undeclared_source_cited");
                        assertThat(row.getResultId()).isNull();
                    });
            var result = results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                    review.workspaceId(), review.reviewId());
            assertThat(result)
                    .singleElement()
                    .satisfies(row -> assertThat(row.getPracticeId()).isEqualTo(sibling.getId()));
            assertThat(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(
                            review.workspaceId(), result.getFirst().getId()))
                    .hasSize(1);
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var prepared =
                    admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));
            assertThat(basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(
                            review.workspaceId(), prepared.feedbackIds().getFirst()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getResultId())
                            .isEqualTo(result.getFirst().getId()));
        }
    }

    @Test
    void shouldPostTheAdmittedObservationOnThePullRequestWhenTheReviewedVersionIsStillCurrent() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));

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

            var replay = admitStaged(review.identity(), request(review));
            assertThat(replay.admissionDigest()).isEqualTo(response.admissionDigest());
            assertThat(replay.admitted())
                    .extracting(AdmittedResultDTO::resultId)
                    .containsExactly(result.getId());
            assertThatThrownBy(() -> admitStaged(
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
                    .satisfies(comment -> assertThat(comment.body()).contains(PostedText.of(SUMMARY)));
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
    void shouldStoreNoFeedbackWhenTheComposedTextLinksBeyondTheCompositionInput() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();

            assertThatThrownBy(() -> admission.prepareFeedback(
                            review.identity(),
                            composition(
                                    review,
                                    response,
                                    SUMMARY + " See [the fix](https://other.example.net/fix).",
                                    context)))
                    .isInstanceOfSatisfying(FeedbackTextRefusedException.class, refused -> {
                        assertThat(refused.rule()).isEqualTo("LINK");
                        assertThat(refused.field()).isEqualTo("summary");
                    });

            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();
            assertThat(admission
                            .prepareFeedback(review.identity(), composition(review, response, SUMMARY, context))
                            .feedbackIds())
                    .containsExactly(FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId()));
        }
    }

    @Test
    void shouldWithholdTheFeedbackAsNotComposedWhenTheFinalComposerAttemptHasNoTimeLeft() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();

            review.inputs().close();
            job.setAttempt(agentProperties.maxRetries());
            jobs.saveAndFlush(job);
            // The final composition window has expired, so it withholds without a model call.
            var composer = new WorkerFeedbackComposer(
                    admission,
                    continuation,
                    mock(ReviewModelGateway.class),
                    assignments,
                    descriptors,
                    reviewProperties,
                    agentProperties,
                    meters,
                    mapper,
                    new CompositionCapacity(4));
            composer.composeResumed(job, WORKER, Instant.now());

            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .isNotEmpty()
                    .allSatisfy(withheld -> {
                        assertThat(withheld.getState()).isEqualTo(FeedbackState.WITHHELD);
                        assertThat(withheld.getStopReason()).isEqualTo(FeedbackStopReason.NOT_COMPOSED);
                    })
                    .extracting(Feedback::getDestination)
                    .contains(FeedbackDestination.ON_WORK);
            // The composition left the attempt running and owned, so the worker's terminal write completes it.
            Integer completed = transactions.execute(status -> jobs.transitionStatusOwnedBy(
                    job.getId(), ReviewJobStatus.COMPLETED, Instant.now(), null, WORKER, job.getAttempt()));
            assertThat(completed).isOne();
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
        }
    }

    @Test
    void shouldReadTheAcceptedReviewOnTheWorkBackForAnAttemptThatComposesAfterIt() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.prepareFeedback(review.identity(), composition(review, response, SUMMARY, context));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();

            assertThat(admission.preparedReview(job)).hasValueSatisfying(prepared -> {
                assertThat(prepared.summary()).isEqualTo(SUMMARY);
                assertThat(prepared.summaryBasis())
                        .extracting(FeedbackCompositionTrace.Support::resultId)
                        .containsExactly(response.admitted().getFirst().resultId());
                assertThat(prepared.notes()).isEmpty();
                assertThat(prepared.withheld()).isEmpty();
            });
        }
    }

    @Test
    void shouldReplayTheAcceptedPackageButWithholdItsSendWhenAnotherReviewPostedOnThePullRequestSinceAcceptance() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
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
            var response = admitStaged(review.identity(), request(review));
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
            var response = admitStaged(review.identity(), request(review));
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
                    .satisfies(comment -> assertThat(comment.body()).contains(PostedText.of(SUMMARY)));
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
        return claimedReview(mode, DefinitionTestFixtures.definition());
    }

    private ClaimedReview claimedReview(FeedbackMode mode, PracticeDefinition definition) {
        var scenario = data.scenario(definition);
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
        var captured = capture(record);
        var inputs = files.prepare(
                job,
                new PreparedEvidence(
                        Map.of(RECORD, record, "INDEX.json", mapper.writeValueAsBytes(captured.path("manifest"))),
                        null),
                null);
        job.setLeasedUntil(Instant.now().plusSeconds(3600));
        job.setCapture(captured);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://model.example",
                                "contract-model",
                                null,
                                null,
                                null,
                                null,
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        200_000,
                        4_000,
                        600,
                        false,
                        null)
                .toJson(mapper));
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
                inputs,
                pending);
    }

    /** What the worker's capture recorded: the pull request record, staged and citable as one text. */
    private ObjectNode capture(byte[] record) {
        String sha256 = ProvenanceDigest.sha256Hex(record);
        var capture = EvidenceSnapshotFixtures.snapshot(mapper);
        var source = EvidenceSnapshotFixtures.availableSource(capture, "scm.pull-request.core", null);
        EvidenceSnapshotFixtures.file(capture, source, RECORD, sha256).put("bytes", record.length);
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

    private AdmissionResponseDTO admitStaged(
            ResultAdmissionService.AdmissionIdentity identity, AdmissionRequestDTO request) {
        for (var result : request.results())
            admission.stage(
                    identity,
                    new AdmissionRequestDTO(
                            3, request.reviewId(), request.jobId(), request.attempt(), List.of(result), List.of()));
        for (var nonResult : request.nonResults())
            admission.stage(
                    identity,
                    new AdmissionRequestDTO(
                            3, request.reviewId(), request.jobId(), request.attempt(), List.of(), List.of(nonResult)));
        return admission.admit(
                identity, new SealResultsRequestDTO(3, request.reviewId(), request.jobId(), request.attempt()));
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
             "coverage":{"source":"host","reads":1,"truncatedReads":0,"failedReads":0,"receipts":[],"complete":true}}
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
            PreparedJobInputs inputs,
            ReviewAdmissionData.Pending pending)
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
