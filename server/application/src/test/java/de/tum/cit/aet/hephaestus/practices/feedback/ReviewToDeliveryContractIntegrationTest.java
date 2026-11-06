package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.Application;
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
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRecovery;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobZombieSweeper;
import de.tum.cit.aet.hephaestus.agent.proxy.ReviewModelGateway;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.RuntimeNonResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.runtime.WorkerGraphWithoutCluster;
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
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendRecovery;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
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
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedGitHub;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.transaction.PlatformTransactionManager;
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
    private static final UUID WORKER_LEASE = UUID.fromString("3e9b7c41-58d2-4a6f-b0e1-7c2d9a4f6b18");
    private static final Duration KILLED_WORKER_LEASE = Duration.ofSeconds(5);
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
    private ReviewJobZombieSweeper sweeper;

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
            var replacement = UUID.fromString("5c1f8e2a-93b4-4d7e-a6c0-2b8f4e1d9a37");
            before.setLeaseId(replacement);
            before.setLeasedUntil(Instant.now().plusSeconds(3600));
            jobs.saveAndFlush(before);
            var identity =
                    new ResultAdmissionService.AdmissionIdentity(before.getId(), review.workspaceId(), 1, replacement);
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
    void shouldWithholdAPracticeThatFailedWhileItsAttemptRunsAndJudgeItsSibling() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var scenario = review.pending().scenario();
            var sibling = versions.create(new PracticeVersionService.NewPractice(
                    scenario.workspace(),
                    "judged-sibling",
                    scenario.item().getKind(),
                    DefinitionTestFixtures.definition(),
                    null,
                    1,
                    review.developerId()));
            admissions.assigned(review.pending(), sibling);
            var identity = review.identity();
            assertThat(jobs.findById(identity.jobId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewJobStatus.RUNNING);
            var sent = request(review).results().getFirst();

            // The runner stages each practice as it ends, while its attempt still runs: here a script that failed
            // to load, then a sibling with a result.
            admission.stage(
                    identity,
                    new AdmissionRequestDTO(
                            3,
                            review.reviewId(),
                            identity.jobId(),
                            identity.attempt(),
                            List.of(),
                            List.of(new RuntimeNonResultDTO(
                                    review.practiceSlug(), ReviewStopReason.EXECUTION_FAILED))));
            admission.stage(
                    identity,
                    new AdmissionRequestDTO(
                            3,
                            review.reviewId(),
                            identity.jobId(),
                            identity.attempt(),
                            List.of(new SubmittedResultDTO(
                                    sibling.getSlug(),
                                    sent.itemId(),
                                    sent.itemVersion(),
                                    sent.candidate(),
                                    sent.readReceipts())),
                            List.of()));
            var sealed = admission.admit(
                    identity, new SealResultsRequestDTO(3, review.reviewId(), identity.jobId(), identity.attempt()));

            assertThat(sealed.admitted())
                    .singleElement()
                    .satisfies(value -> assertThat(value.practiceSlug()).isEqualTo(sibling.getSlug()));
            assertThat(jdbc.queryForObject(
                            "SELECT state || '/' || reason FROM review_item WHERE workspace_id=? AND review_id=? AND"
                                    + " practice_id=?",
                            String.class,
                            review.workspaceId(),
                            review.reviewId(),
                            review.practiceId()))
                    .isEqualTo("WITHHELD/EXECUTION_FAILED");
            assertThat(resultIds(review)).hasSize(1);
        }
    }

    @Test
    void shouldSealTheValidPracticeWhenItsSiblingEvidenceExpired() {
        double recordedBefore = recorded();
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
            assertThat(recorded())
                    .as("the sealed result counts once its review commits")
                    .isEqualTo(recordedBefore + 1);
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
        try (var review = claimedReview(FeedbackMode.AUTOMATIC);
                var logs = new LogCapture("de.tum.cit.aet.hephaestus.practices.review.ReviewOutcomeMeters")) {
            Map<Meter.Id, Double> endedBefore = meters.find(PracticeMetrics.REVIEW_ENDED).counters().stream()
                    .collect(Collectors.toMap(Counter::getId, Counter::count));
            double withheldBefore = count(
                    PracticeMetrics.REVIEW_ITEMS_ENDED, "kind", "item", "state", "withheld", "reason", "cancelled");
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
            var ended = jdbc.queryForMap(
                    "SELECT lower(status) AS status,lower(stop_reason) AS reason FROM review WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    review.reviewId());
            assertThat(meters.find(PracticeMetrics.REVIEW_ENDED).counters().stream()
                            .filter(counter -> counter.count() != endedBefore.getOrDefault(counter.getId(), 0.0))
                            .map(counter -> counter.getId().getTag("status") + "/"
                                    + counter.getId().getTag("reason") + "/"
                                    + (counter.count() - endedBefore.getOrDefault(counter.getId(), 0.0))))
                    .as("the review ends once, with its own status and stop reason")
                    .containsExactly(ended.get("status") + "/" + ended.get("reason") + "/1.0");
            assertThat(logs.events("practice_review.ended"))
                    .filteredOn(fields -> review.reviewId().toString().equals(fields.get("review.id")))
                    .singleElement()
                    .satisfies(fields -> {
                        assertThat(fields).containsEntry("review.status", String.valueOf(ended.get("status")));
                        assertThat(fields).containsEntry("review.reason", String.valueOf(ended.get("reason")));
                    });
            assertThat(count(
                            PracticeMetrics.REVIEW_ITEMS_ENDED,
                            "kind",
                            "item",
                            "state",
                            "withheld",
                            "reason",
                            "cancelled"))
                    .isEqualTo(withheldBefore + 1);
        }
    }

    private long firstDeliveries() {
        return meters.find(PracticeMetrics.REVIEW_FIRST_DELIVERY).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    private double recorded() {
        return meters.find(PracticeMetrics.RESULT_RECORDED).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private double count(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void shouldDiscardExpiredCitationsWhileRunningAndKeepItsRefusalForTheSeal() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            jdbc.update(
                    "UPDATE review_item SET staged_payload=jsonb_set(staged_payload,'{evidence,expiresAt}',to_jsonb(CAST(? AS text)))"
                            + " WHERE workspace_id=? AND review_id=? AND practice_id=? AND item_id=?",
                    Instant.now().minusSeconds(1).toString(),
                    review.workspaceId(),
                    review.reviewId(),
                    review.practiceId(),
                    review.item().itemId());
            stagingStore.discardUnusable(Instant.now(), 1000);
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
                    status -> attemptRecovery.recover(job.getId(), WORKER_LEASE, job.getAttempt(), null));
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            var last = jobs.findById(job.getId()).orElseThrow();
            last.setStatus(ReviewJobStatus.RUNNING);
            last.setLeaseId(WORKER_LEASE);
            last.setLeasedUntil(Instant.now().plusSeconds(3600));
            jobs.saveAndFlush(last);
            transactions.executeWithoutResult(
                    status -> attemptRecovery.recover(last.getId(), WORKER_LEASE, last.getAttempt(), null));
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
            transactions.executeWithoutResult(status -> attemptRecovery.recover(job.getId(), WORKER_LEASE, 0, null));
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

    /**
     * A worker process that holds an attempt with a staged practice, killed with no chance to clean up. While it renews
     * its lease, the server's lease check leaves the attempt alone; once it is dead, the lease runs out and the same
     * check gives the review a new attempt, with the staged practice kept. The in-process test above calls the
     * recovery itself; this one leaves it to the production sweep, on a lease that really expires.
     *
     * <p>The worker leases for {@link #KILLED_WORKER_LEASE} instead of the production five minutes, so the lease ends
     * within the test; the sweep and its renewal interval are the production ones.
     */
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void shouldKeepStagedPracticesAndReclaimTheLeaseWhenTheWorkerProcessIsKilled() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            UUID jobId = review.identity().jobId();
            Path request = Files.createTempFile("lost-worker-request", ".json");
            Files.write(request, mapper.writeValueAsBytes(request(review)));
            var postgres = PostgreSQLTestContainer.getInstance();
            var builder = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            // A second application context on a CI runner beside the test's own: bound its heap.
                            "-Xmx768m",
                            "-cp",
                            System.getProperty("java.class.path"),
                            LostWorker.class.getName(),
                            jobId.toString(),
                            Long.toString(review.workspaceId()),
                            WORKER_LEASE.toString(),
                            request.toString(),
                            Long.toString(KILLED_WORKER_LEASE.toSeconds()))
                    .redirectErrorStream(true);
            builder.environment().put("LOST_WORKER_JDBC_URL", postgres.getJdbcUrl());
            builder.environment().put("LOST_WORKER_JDBC_USER", postgres.getUsername());
            builder.environment().put("LOST_WORKER_JDBC_PASSWORD", postgres.getPassword());
            Process worker = builder.start();
            Duration renewEvery = agentProperties.heartbeatInterval();
            try {
                // A reader thread drains the output, so the worker never blocks on a full pipe and a stalled worker
                // fails the wait instead of hanging it.
                var lines = new LinkedBlockingQueue<String>();
                Thread.ofVirtual().start(() -> {
                    try (var output = new BufferedReader(
                            new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = output.readLine()) != null; ) lines.add(line);
                    } catch (IOException ended) {
                        // The worker was killed.
                    }
                });
                awaitLine(lines, LostWorker.STAGED, Duration.ofMinutes(3));
                awaitLine(lines, LostWorker.RENEWED, Duration.ofSeconds(30));
                Instant renewedOnce = leasedUntil(jobId);
                awaitLine(lines, LostWorker.RENEWED, Duration.ofSeconds(30));
                assertThat(leasedUntil(jobId))
                        .as("the worker process renews its lease with the heartbeat's statement")
                        .isAfter(renewedOnce);

                // Without its renewals the lease would run out and be taken within the sweep's quiet time after a
                // start plus its one-interval cutoff; the attempt stays the worker's for longer than both.
                Instant alive = Instant.now().plus(renewEvery.multipliedBy(2)).plusSeconds(5);
                while (Instant.now().isBefore(alive)) {
                    sweeper.recoverOrphanedJobs();
                    assertThat(worker.isAlive()).isTrue();
                    assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                            .as("the attempt of a worker that renews its lease")
                            .isEqualTo(ReviewJobStatus.RUNNING);
                    Thread.sleep(1000);
                }
            } finally {
                worker.destroyForcibly();
                assertThat(worker.waitFor(30, TimeUnit.SECONDS)).isTrue();
                Files.deleteIfExists(request);
            }

            var orphan = jobs.findById(jobId).orElseThrow();
            assertThat(orphan.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(orphan.getLeaseId())
                    .as("nothing in the killed process released its lease")
                    .isEqualTo(WORKER_LEASE);
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), jobId))
                    .as("the practice the worker staged before it died")
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());

            Instant deadline = Instant.now()
                    .plus(KILLED_WORKER_LEASE)
                    .plus(renewEvery.multipliedBy(2))
                    .plusSeconds(30);
            while (jobs.findById(jobId).orElseThrow().getStatus() == ReviewJobStatus.RUNNING
                    && Instant.now().isBefore(deadline)) {
                sweeper.recoverOrphanedJobs();
                Thread.sleep(1000);
            }

            var retry = jobs.findById(jobId).orElseThrow();
            assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(retry.getAttempt()).isEqualTo(1);
            assertThat(retry.getLeaseId()).isNull();
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), jobId))
                    .as("the next attempt starts with the practice already staged")
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());
            assertThat(resultIds(review)).isEmpty();
        }
    }

    private Instant leasedUntil(UUID jobId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT leased_until FROM review_job WHERE id = ?", Instant.class, jobId));
    }

    /** Takes the worker's output lines until one holds {@code marker}; fails on its failure line or the deadline. */
    private static void awaitLine(BlockingQueue<String> lines, String marker, Duration within)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) continue;
            if (line.contains(marker)) return;
            if (line.startsWith(LostWorker.FAILED)) throw new AssertionError(line);
        }
        throw new AssertionError("The worker process did not print " + marker + " within " + within);
    }

    /**
     * A worker process: the application with only the worker role, on the test's database, without a cluster. It
     * stages one practice for the claimed attempt through the worker's admission service, then renews the attempt's
     * lease with the heartbeat's statement until it is killed. It does not run the review: no sandbox starts.
     */
    public static final class LostWorker {
        static final String STAGED = "LOST-WORKER STAGED";
        static final String RENEWED = "LOST-WORKER RENEWED";
        static final String FAILED = "LOST-WORKER FAILED";

        public static void main(String[] args) throws Exception {
            UUID jobId = UUID.fromString(args[0]);
            long workspaceId = Long.parseLong(args[1]);
            UUID leaseId = UUID.fromString(args[2]);
            byte[] request = Files.readAllBytes(Path.of(args[3]));
            long leaseSeconds = Long.parseLong(args[4]);
            var configuration = BootstrapUtils.resolveTestContextBootstrapper(WorkerGraphWithoutCluster.class)
                    .buildMergedContextConfiguration();
            new WebApplicationContextRunner()
                    .withInitializer(application -> {
                        var conversion = new ApplicationConversionService();
                        application.getEnvironment().setConversionService(conversion);
                        application.getBeanFactory().setConversionService(conversion);
                    })
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(application -> configuration
                            .getContextCustomizers()
                            .forEach(customizer -> customizer.customizeContext(application, configuration)))
                    .withUserConfiguration(Application.class)
                    .withPropertyValues(
                            "spring.profiles.active=worker",
                            "spring.config.import=classpath:application-test.yml",
                            "spring.main.lazy-initialization=false",
                            "spring.sql.init.mode=never",
                            "spring.liquibase.enabled=false",
                            "spring.datasource.url=" + System.getenv("LOST_WORKER_JDBC_URL"),
                            "spring.datasource.username=" + System.getenv("LOST_WORKER_JDBC_USER"),
                            "spring.datasource.password=" + System.getenv("LOST_WORKER_JDBC_PASSWORD"),
                            "spring.datasource.hikari.maximum-pool-size=4",
                            "hephaestus.runtime.server.enabled=false",
                            "hephaestus.runtime.worker.enabled=true",
                            "hephaestus.runtime.webhook.enabled=false",
                            "hephaestus.agent.enabled=true",
                            "hephaestus.worker.capacity.mentor-max=1",
                            "hephaestus.sync.nats.enabled=false",
                            "hephaestus.sandbox.kubernetes.namespace=lost-worker-sandboxes",
                            "hephaestus.sandbox.kubernetes.pod-name=lost-worker",
                            "hephaestus.sandbox.kubernetes.pod-ip=127.0.0.1",
                            "server.port=0",
                            "management.server.port=0")
                    .run(application -> {
                        try {
                            var roles = RuntimeRole.enabled(application.getEnvironment());
                            if (!roles.equals(List.of(RuntimeRole.WORKER))
                                    || !application
                                            .getBeansOfType(ReviewJobZombieSweeper.class)
                                            .isEmpty()) {
                                System.out.println(FAILED + " not a worker-only process: " + roles);
                                return;
                            }
                            var identity = new ResultAdmissionService.AdmissionIdentity(jobId, workspaceId, 0, leaseId);
                            application
                                    .getBean(ResultAdmissionService.class)
                                    .stage(
                                            identity,
                                            application
                                                    .getBean(JsonMapper.class)
                                                    .readValue(request, AdmissionRequestDTO.class));
                            System.out.println(STAGED);
                            var transactions =
                                    new TransactionTemplate(application.getBean(PlatformTransactionManager.class));
                            var repository = application.getBean(ReviewJobRepository.class);
                            while (true) {
                                Integer renewed = transactions.execute(
                                        status -> repository.renewLease(jobId, workspaceId, 0, leaseId, leaseSeconds));
                                if (renewed == null || renewed != 1) {
                                    System.out.println(FAILED + " lease lost");
                                    return;
                                }
                                System.out.println(RENEWED);
                                Thread.sleep(500);
                            }
                        } catch (Exception failed) {
                            System.out.println(FAILED + " " + failed);
                        } finally {
                            System.out.flush();
                        }
                    });
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
                                    review.identity().jobId(), review.workspaceId() + 1000000, 0, WORKER_LEASE),
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
    void shouldRefuseComposedOutputWhenItsResultBelongsToAnotherWorkspace() {
        try (var own = claimedReview(FeedbackMode.AUTOMATIC);
                var other = claimedReview(FeedbackMode.AUTOMATIC)) {
            assertThat(own.workspaceId()).isNotEqualTo(other.workspaceId());
            var ownResults = admitStaged(own.identity(), request(own));
            var foreignResults = admitStaged(other.identity(), request(other));
            var foreign = foreignResults.admitted().getFirst();
            var context = admission.publicFeedbackHistory(own.identity()).publicationContext();
            var forged = composition(foreignResults, SUMMARY, context);
            assertThatThrownBy(() -> admission.checkPackage(own.identity(), forged))
                    .isInstanceOf(AdmissionConflictException.class);
            assertThatThrownBy(() -> admission.publishPackage(own.identity(), forged))
                    .isInstanceOf(AdmissionConflictException.class);

            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(own.workspaceId(), own.reviewId()))
                    .isEmpty();
            assertThat(admission
                            .admittedResponse(
                                    jobs.findById(own.identity().jobId()).orElseThrow())
                            .admitted())
                    .extracting(AdmittedResultDTO::resultId)
                    .containsExactly(ownResults.admitted().getFirst().resultId());
            assertThat(resultIds(other)).containsExactly(foreign.resultId());
            var accepted = admission.publishPackage(own.identity(), composition(ownResults, SUMMARY, context));
            var acceptedId = accepted.pieces().getFirst().feedbackId();
            assertThat(basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(own.workspaceId(), acceptedId))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getResultId())
                            .isEqualTo(ownResults.admitted().getFirst().resultId()));
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(own.workspaceId(), acceptedId))
                    .extracting(FeedbackCopy::getKind)
                    .containsExactly(FeedbackCopyKind.SUMMARY);
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
            var prepared = admission.publishPackage(review.identity(), composition(response, SUMMARY, context));
            assertThat(basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(
                            review.workspaceId(),
                            prepared.pieces().stream()
                                    .map(PreparedFeedback::feedbackId)
                                    .toList()
                                    .getFirst()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.getResultId())
                            .isEqualTo(result.getFirst().getId()));
        }
    }

    @Test
    void shouldCheckWithoutPreparingFeedbackAndRefusePublicationAfterItsLeaseExpires() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var proposal = composition(response, SUMMARY, context);
            admission.checkPackage(review.identity(), proposal);
            var id = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId()))
                    .isEmpty();
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(review.workspaceId(), id))
                    .isEmpty();
            assertThat(sends.findByFeedbackIdAndWorkspaceId(id, review.workspaceId()))
                    .isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            job.setLeasedUntil(Instant.now().minusSeconds(1));
            jobs.saveAndFlush(job);
            assertThatThrownBy(() -> admission.publishPackage(review.identity(), proposal))
                    .isInstanceOf(ResultAdmissionService.StaleAttemptException.class);
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId()))
                    .isEmpty();
            assertThat(github.threads().on(ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber())))
                    .isEmpty();
        }
    }

    @Test
    void shouldRecheckPublicHistoryWhenPublishingAnAlreadyCheckedDraft() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var proposal = composition(response, SUMMARY, context);
            admission.checkPackage(review.identity(), proposal);
            var sibling = RetainedFeedbackTestData.onWork(
                    jdbc, feedback, review.item(), review.developerId(), FeedbackState.DELIVERED);
            copies.saveAndFlush(RetainedFeedbackTestData.posted(
                    sibling, FeedbackCopyKind.SUMMARY, 0, "PRR_other", "https://github.com/review/other"));
            assertThatThrownBy(() -> admission.publishPackage(review.identity(), proposal))
                    .isInstanceOf(PublicationContextRefusedException.class);
            assertThat(feedback.findByIdAndWorkspaceId(
                            FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId()),
                            review.workspaceId()))
                    .isEmpty();
        }
    }

    @Test
    void shouldRefuseComposedOutputThatNamesAnotherWorkspacesCanaryEvidence() {
        try (var owner = claimedReview(FeedbackMode.AUTOMATIC);
                var other = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(owner.identity(), request(owner));
            var foreign = admitStaged(other.identity(), request(other));
            var context = admission.publicFeedbackHistory(owner.identity()).publicationContext();
            var attack = composition(foreign, "CANARY_OTHER_WORKSPACE_COMPOSED_OUTPUT", context);
            assertThatThrownBy(() -> admission.checkPackage(owner.identity(), attack))
                    .isInstanceOf(AdmissionConflictException.class);
            assertThatThrownBy(() -> admission.publishPackage(owner.identity(), attack))
                    .isInstanceOf(AdmissionConflictException.class);
            var id = FeedbackIds.onWork(owner.workspaceId(), owner.reviewId(), owner.developerId());
            assertThat(feedback.findByIdAndWorkspaceId(id, owner.workspaceId())).isEmpty();
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(owner.workspaceId(), id))
                    .isEmpty();
            assertThat(sends.findByFeedbackIdAndWorkspaceId(id, owner.workspaceId()))
                    .isEmpty();
            assertThat(github.threads().on(ScriptedGitHub.pullRequest(owner.repository(), owner.pullRequestNumber())))
                    .isEmpty();
            assertThat(github.threads().on(ScriptedGitHub.pullRequest(other.repository(), other.pullRequestNumber())))
                    .isEmpty();
        }
    }

    @Test
    void shouldPostTheAdmittedObservationOnThePullRequestWhenTheReviewedVersionIsStillCurrent() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            double deliveredBefore = count(
                    PracticeMetrics.FEEDBACK_SETTLED, "destination", "on_work", "state", "delivered", "reason", "none");
            long firstDeliveriesBefore = firstDeliveries();
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
            assertThatThrownBy(() -> admission.publishPackage(review.identity(), composition(response, SUMMARY, stale)))
                    .isInstanceOfSatisfying(
                            PublicationContextRefusedException.class,
                            refused ->
                                    assertThat(refused.reason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED));
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId()))
                    .isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();

            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            var prepared = admission.publishPackage(review.identity(), composition(response, SUMMARY, context));

            assertThat(prepared.pieces().stream()
                            .map(PreparedFeedback::feedbackId)
                            .toList())
                    .containsExactly(feedbackId);
            assertThat(admission
                            .admittedResponse(
                                    jobs.findById(review.identity().jobId()).orElseThrow())
                            .publicReviewed())
                    .isTrue();
            var replayed = admission.publishPackage(review.identity(), composition(response, SUMMARY, context));
            assertThat(replayed.pieces().stream()
                            .map(PreparedFeedback::feedbackId)
                            .toList())
                    .isEqualTo(prepared.pieces().stream()
                            .map(PreparedFeedback::feedbackId)
                            .toList());
            assertThat(replayed.packageDigest()).isEqualTo(prepared.packageDigest());
            assertThatThrownBy(() -> admission.publishPackage(
                            review.identity(),
                            composition(response, "Different words for the same observation.", context)))
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
                    .isEqualTo(prepared.packageDigest());

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
            assertThat(count(
                            PracticeMetrics.FEEDBACK_SETTLED,
                            "destination",
                            "on_work",
                            "state",
                            "delivered",
                            "reason",
                            "none"))
                    .as("the delivery counts; recovery can also settle sends that earlier tests left")
                    .isGreaterThan(deliveredBefore);
            assertThat(firstDeliveries())
                    .as("the review's first delivery is observed")
                    .isGreaterThan(firstDeliveriesBefore);
            assertThat(feedback.oldestWaitingOnWorkSeconds())
                    .as("the freshness query runs against the schema")
                    .satisfiesAnyOf(
                            age -> assertThat(age).isNull(),
                            age -> assertThat(age).isNotNegative());
        }
    }

    @Test
    void shouldStoreNoFeedbackWhenTheComposedTextLinksBeyondTheCompositionInput() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();

            assertThatThrownBy(() -> admission.publishPackage(
                            review.identity(),
                            composition(response, SUMMARY + " See [the fix](https://other.example.net/fix).", context)))
                    .isInstanceOfSatisfying(FeedbackTextRefusedException.class, refused -> {
                        assertThat(refused.rule()).isEqualTo("LINK");
                        assertThat(refused.field()).isEqualTo("summary");
                    });

            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();
            assertThat(
                            admission
                                    .publishPackage(review.identity(), composition(response, SUMMARY, context))
                                    .pieces()
                                    .stream()
                                    .map(PreparedFeedback::feedbackId)
                                    .toList())
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
            composer.composeResumed(job, WORKER_LEASE, Instant.now());

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
                    job.getId(), ReviewJobStatus.COMPLETED, Instant.now(), null, WORKER_LEASE, job.getAttempt()));
            assertThat(completed).isOne();
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
        }
    }

    @Test
    void shouldReadTheAcceptedReviewOnTheWorkBackForAnAttemptThatComposesAfterIt() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            admission.publishPackage(review.identity(), composition(response, SUMMARY, context));
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
            var composed = composition(response, SUMMARY, context);
            var prepared = admission.publishPackage(review.identity(), composed);
            var feedbackId = FeedbackIds.onWork(review.workspaceId(), review.reviewId(), review.developerId());
            var sibling = RetainedFeedbackTestData.onWork(
                    jdbc, feedback, review.item(), review.developerId(), FeedbackState.DELIVERED);
            copies.saveAndFlush(RetainedFeedbackTestData.posted(
                    sibling, FeedbackCopyKind.SUMMARY, 0, "IC_sibling", "https://github.com/comment"));
            assertThat(admission.publicFeedbackHistory(review.identity()).publicationContext())
                    .isNotEqualTo(context);

            // An accepted public package replays by its digest; the history it was composed from is not read again.
            var replayed = admission.publishPackage(review.identity(), composed);

            assertThat(replayed.pieces().stream()
                            .map(PreparedFeedback::feedbackId)
                            .toList())
                    .isEqualTo(prepared.pieces().stream()
                            .map(PreparedFeedback::feedbackId)
                            .toList());
            assertThat(replayed.packageDigest()).isEqualTo(prepared.packageDigest());

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
            admission.publishPackage(review.identity(), composition(response, SUMMARY, context));
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
            admission.publishPackage(review.identity(), composition(response, SUMMARY, context));
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

    /** A running attempt of one ITEM review of a GitHub pull request, claimed by {@link #WORKER_LEASE}, with its capture. */
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
        var monitor = WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId());
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
                "UPDATE review_job SET lease_id=?, leased_until=now() + interval '10 minutes' WHERE workspace_id=? AND id=?",
                WORKER_LEASE,
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
                        null)
                .toJson(mapper));
        jobs.saveAndFlush(job);
        return new ClaimedReview(
                new ResultAdmissionService.AdmissionIdentity(job.getId(), workspace.getId(), 0, WORKER_LEASE),
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
    private static FeedbackPackage composition(
            AdmissionResponseDTO admitted, String summary, @Nullable String publicationContext) {
        var result = admitted.admitted().getFirst();
        var problem = result.problems().getFirst().id();
        var support = List.of(new FeedbackCompositionTrace.Support(result.resultId(), problem));
        return new FeedbackPackage(
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
