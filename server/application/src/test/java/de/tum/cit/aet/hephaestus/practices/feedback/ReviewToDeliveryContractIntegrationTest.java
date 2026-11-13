package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmAuthMode;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionSessions;
import de.tum.cit.aet.hephaestus.agent.composer.CompositionStop;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
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
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewComposeRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionBasisRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobCompletionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewSandboxRuns;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.RuntimeNonResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SealResultsRequestDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.SubmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.ComposeRunGateway;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.AgentRuntimeWithoutCluster;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.CitationSide;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.PostedText;
import de.tum.cit.aet.hephaestus.observability.LogCapture;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
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
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackReadbackObservedEvent;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.AfterCommitCheckpoint;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One item review from the worker's admission to its posted copy on the pull request. Admission, preparation, the
 * gate, the target resolver and the send state machine are the application's own beans; only GitHub's GraphQL
 * transport is scripted.
 */
class ReviewToDeliveryContractIntegrationTest extends BaseIntegrationTest {
    private static final Duration KILLED_SERVER_LEASE = Duration.ofSeconds(5);
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
    private FeedbackRepair repairs;

    @Autowired
    private ReviewJobAssignments assignments;

    @Autowired
    private ItemDescriptorRegistry descriptors;

    @Autowired
    private PracticeReviewProperties reviewProperties;

    @Autowired
    private AgentRuns agentRuns;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private FailedEventPublications failedPublications;

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
    private FeedbackSendQueue sendQueue;

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
    private ActionExecutor executor;

    @Autowired
    private FeedbackDecisionService decisions;

    @Autowired
    private FeedbackContentDigest digests;

    @Autowired
    private CredentialBundleConverter credentials;

    private boolean silencedBefore;

    @Autowired
    private ReviewStart reviewStart;

    @Autowired
    private CompositionToolTrace toolTrace;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PracticeStagingStore stagingStore;

    @Autowired
    private ReviewRunKind reviewRuns;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private ReviewJobCompletionService completion;

    @Autowired
    private ReviewCompositionBasisRepository compositionBases;

    @Autowired
    private ApplicationContext context;

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
            RuntimeJobTestData.reclaim(jdbc, before);
            var identity = new ResultAdmissionService.AdmissionIdentity(
                    before.getId(), review.workspaceId(), 1, before.getRunId());
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
            RuntimeJobTestData.updateJobs(
                    jdbc,
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

    /** One model call of the job's current attempt that ended with this measured usage. */
    private void measuredCall(ReviewJob job, long input, long output, long cacheRead, long cacheWrite) {
        jdbc.update("""
                INSERT INTO llm_usage_event (id, workspace_id, run_id, lease_epoch, call_state, job_type, source_type,
                    source_id, source_attempt, review_id, model_kind, input_tokens, output_tokens, cache_read_tokens,
                    cache_write_tokens, pricing_state, funding_source, cost_usd, usage_provenance,
                    measurement_verification, started_at, occurred_at)
                SELECT gen_random_uuid(), j.workspace_id, j.run_id, r.lease_epoch, 'ENDED', 'PRACTICE_REVIEW',
                    'REVIEW_JOB', j.id, r.lease_epoch - 1, j.review_id, 'CHAT', ?, ?, ?, ?, 'NO_CHARGE', 'INSTANCE', 0,
                    'PROXY', 'VERIFIED', now(), now()
                FROM review_job j JOIN agent_run r ON r.id = j.run_id WHERE j.id = ?
                """, input, output, cacheRead, cacheWrite, job.getId());
    }

    @Test
    void shouldBoundTheFinalStagedHandoffAndKeepItsMeasuredUsage() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admission.stage(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            job.setExecutionStartedAt(Instant.now());
            jobs.saveAndFlush(job);
            measuredCall(job, 1, 0, 0, 0);
            int limit = agentRuns.capacity().reviewRetries();
            // Every retry is lost: losing the attempt after them leaves only the handoff that seals what it staged.
            for (int lost = 0; lost < limit; lost++) {
                reapLost(job);
                claimAgain(job);
            }
            reapLost(job);
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(jobs.findById(job.getId()).orElseThrow().spentAttempts()).isEqualTo(limit + 1);
            var last = claimAgain(job);
            reapLost(last);
            assertThat(jobs.findById(last.getId()).orElseThrow().getStatus())
                    .as("the handoff was lost too: the retry limit fails the review")
                    .isEqualTo(ReviewJobStatus.FAILED);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM llm_usage_event WHERE workspace_id=? AND source_id=? AND source_attempt=?",
                            Integer.class,
                            review.workspaceId(),
                            job.getId(),
                            0))
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
            RuntimeJobTestData.leaseUntil(jdbc, job, Instant.now().minusSeconds(1));
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
            jobs.saveAndFlush(job);
            measuredCall(job, 13, 7, 1200, 300);
            reapLost(job);
            var retry = jobs.findById(job.getId()).orElseThrow();
            assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(retry.getAttempt()).isEqualTo(1);
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), job.getId()))
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());
            assertThat(jdbc.queryForMap(
                            "SELECT call_state,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens"
                                    + " FROM llm_usage_event WHERE workspace_id=? AND source_id=? AND source_attempt=0",
                            review.workspaceId(),
                            job.getId()))
                    .containsEntry("call_state", "ENDED")
                    .containsEntry("input_tokens", 13L)
                    .containsEntry("output_tokens", 7L)
                    .containsEntry("cache_read_tokens", 1200L)
                    .containsEntry("cache_write_tokens", 300L);
            assertThat(resultIds(review)).isEmpty();
        }
    }

    /**
     * A server process that holds an attempt with a staged practice, killed with no chance to clean up. While it renews
     * its lease, the server's lease check leaves the attempt alone; once it is dead, the lease runs out and the same
     * check gives the review a new attempt, with the staged practice kept. The in-process test above calls the
     * recovery itself; this one leaves it to the production sweep, on a lease that really expires.
     *
     * <p>The killed server leases for {@link #KILLED_SERVER_LEASE} instead of the production five minutes, so the lease ends
     * within the test; the sweep and its renewal interval are the production ones.
     */
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void shouldKeepStagedPracticesAndReclaimTheLeaseWhenTheServerProcessIsKilled() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            UUID jobId = review.identity().jobId();
            Path request = Files.createTempFile("lost-server-request", ".json");
            Files.write(request, mapper.writeValueAsBytes(request(review)));
            var postgres = PostgreSQLTestContainer.getInstance();
            var builder = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java")
                                    .toString(),
                            // A second application context on a CI runner beside the test's own: bound its heap.
                            "-Xmx768m",
                            "-cp",
                            System.getProperty("java.class.path"),
                            LostServer.class.getName(),
                            jobId.toString(),
                            Long.toString(review.workspaceId()),
                            review.identity().runId().toString(),
                            request.toString(),
                            Long.toString(KILLED_SERVER_LEASE.toSeconds()))
                    .redirectErrorStream(true);
            builder.environment().put("LOST_SERVER_JDBC_URL", postgres.getJdbcUrl());
            builder.environment().put("LOST_SERVER_JDBC_USER", postgres.getUsername());
            builder.environment().put("LOST_SERVER_JDBC_PASSWORD", postgres.getPassword());
            Process lost = builder.start();
            Duration renewEvery = agentRuns.capacity().leaseRenewal();
            try {
                // A reader thread drains the output, so the lost server never blocks on a full pipe and a stalled
                // server
                // fails the wait instead of hanging it.
                var lines = new LinkedBlockingQueue<String>();
                Thread.ofVirtual().start(() -> {
                    try (var output =
                            new BufferedReader(new InputStreamReader(lost.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = output.readLine()) != null; ) lines.add(line);
                    } catch (IOException ended) {
                        // The server was killed.
                    }
                });
                awaitLine(lines, LostServer.STAGED, Duration.ofMinutes(3));
                awaitLine(lines, LostServer.RENEWED, Duration.ofSeconds(30));
                Instant renewedOnce = leasedUntil(jobId);
                awaitLine(lines, LostServer.RENEWED, Duration.ofSeconds(30));
                assertThat(leasedUntil(jobId))
                        .as("the server process renews its lease with the heartbeat's statement")
                        .isAfter(renewedOnce);

                // Without its renewals the lease would run out and be taken within the sweep's quiet time after a
                // start plus its one-interval cutoff; the attempt stays the lost server's for longer than both.
                Instant alive = Instant.now().plus(renewEvery.multipliedBy(2)).plusSeconds(5);
                while (Instant.now().isBefore(alive)) {
                    reaper.reap();
                    assertThat(lost.isAlive()).isTrue();
                    assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                            .as("the attempt of a server that renews its lease")
                            .isEqualTo(ReviewJobStatus.RUNNING);
                    Thread.sleep(1000);
                }
            } finally {
                lost.destroyForcibly();
                assertThat(lost.waitFor(30, TimeUnit.SECONDS)).isTrue();
                Files.deleteIfExists(request);
            }

            var orphan = jobs.findById(jobId).orElseThrow();
            assertThat(orphan.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(RuntimeJobTestData.runState(jdbc, orphan))
                    .as("nothing in the killed process released its lease")
                    .isEqualTo("LEASED");
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), jobId))
                    .as("the practice the lost server staged before it died")
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());

            Instant deadline = Instant.now()
                    .plus(KILLED_SERVER_LEASE)
                    .plus(renewEvery.multipliedBy(2))
                    .plusSeconds(30);
            while (jobs.findById(jobId).orElseThrow().getStatus() == ReviewJobStatus.RUNNING
                    && Instant.now().isBefore(deadline)) {
                reaper.reap();
                Thread.sleep(1000);
            }

            var retry = jobs.findById(jobId).orElseThrow();
            assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(retry.getAttempt()).isEqualTo(1);
            assertThat(RuntimeJobTestData.runState(jdbc, retry)).isEqualTo("QUEUED");
            assertThat(stagingStore.staged(review.workspaceId(), review.reviewId(), jobId))
                    .as("the next attempt starts with the practice already staged")
                    .singleElement()
                    .satisfies(staged -> assertThat(staged.attempt()).isZero());
            assertThat(resultIds(review)).isEmpty();
        }
    }

    private Instant leasedUntil(UUID jobId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT r.leased_until FROM review_job j JOIN agent_run r ON r.id = j.run_id WHERE j.id = ?",
                Instant.class,
                jobId));
    }

    /** The attempt's holder stopped renewing its run, and the reaper takes it back. */
    private void reapLost(ReviewJob job) {
        RuntimeJobTestData.leaseUntil(jdbc, job, Instant.now().minusSeconds(1));
        reaper.reap();
    }

    /** Claims the requeued job again, as the next claim of its run does. */
    private ReviewJob claimAgain(ReviewJob job) {
        var queued = jobs.findById(job.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        queued.setStatus(ReviewJobStatus.RUNNING);
        return RuntimeJobTestData.save(transactions, jdbc, jobs, queued);
    }

    /** Takes the lost server's output lines until one holds {@code marker}; fails on its failure line or the deadline. */
    private static void awaitLine(BlockingQueue<String> lines, String marker, Duration within)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line == null) continue;
            if (line.contains(marker)) return;
            if (line.startsWith(LostServer.FAILED)) throw new AssertionError(line);
        }
        throw new AssertionError("The lost server process did not print " + marker + " within " + within);
    }

    /**
     * A server process with agent jobs on, on the test's database, without a cluster. It stages one practice for the
     * claimed attempt through its admission service, then renews the attempt's lease with the heartbeat's statement until
     * it is killed. It does not run the review: no sandbox starts. The test profile keeps its schedules and startup sync
     * off, so the test's own server does all the recovery.
     */
    public static final class LostServer {
        static final String STAGED = "LOST-SERVER STAGED";
        static final String RENEWED = "LOST-SERVER RENEWED";
        static final String FAILED = "LOST-SERVER FAILED";

        public static void main(String[] args) throws Exception {
            UUID jobId = UUID.fromString(args[0]);
            long workspaceId = Long.parseLong(args[1]);
            UUID runId = UUID.fromString(args[2]);
            byte[] request = Files.readAllBytes(Path.of(args[3]));
            long leaseSeconds = Long.parseLong(args[4]);
            var configuration = BootstrapUtils.resolveTestContextBootstrapper(AgentRuntimeWithoutCluster.class)
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
                            "spring.profiles.active=test",
                            "spring.config.import=classpath:application-test.yml",
                            "spring.main.lazy-initialization=false",
                            "spring.liquibase.enabled=false",
                            "spring.datasource.url=" + System.getenv("LOST_SERVER_JDBC_URL"),
                            "spring.datasource.username=" + System.getenv("LOST_SERVER_JDBC_USER"),
                            "spring.datasource.password=" + System.getenv("LOST_SERVER_JDBC_PASSWORD"),
                            "spring.datasource.hikari.maximum-pool-size=4",
                            "hephaestus.runtime.server.enabled=true",
                            "hephaestus.agent.enabled=true",
                            "hephaestus.sync.nats.enabled=false",
                            "hephaestus.sandbox.kubernetes.namespace=lost-server-sandboxes",
                            "server.port=0",
                            "management.server.port=0")
                    .run(application -> {
                        try {
                            var roles = RuntimeRole.enabled(application.getEnvironment());
                            if (!roles.equals(List.of(RuntimeRole.SERVER))
                                    || application
                                            .getBeansOfType(ReviewSandboxRuns.class)
                                            .isEmpty()) {
                                System.out.println(FAILED + " not a server with agent jobs: " + roles);
                                return;
                            }
                            var identity = new ResultAdmissionService.AdmissionIdentity(jobId, workspaceId, 0, runId);
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
                            var runs = application.getBean(AgentRuns.class);
                            var fence = new AgentRunFence(runId, workspaceId, ReviewRunKind.epoch(0));
                            var jdbc = application.getBean(JdbcTemplate.class);
                            while (true) {
                                // The renewal of the executor that holds the run, on a lease short enough to expire
                                // within the test once the process is killed.
                                Boolean renewed = transactions.execute(
                                        status -> runs.renew(fence).isPresent()
                                                && jdbc.update(
                                                                "UPDATE agent_run SET leased_until = clock_timestamp()"
                                                                        + " + make_interval(secs => ?) WHERE id = ?",
                                                                leaseSeconds,
                                                                runId)
                                                        == 1);
                                if (!Boolean.TRUE.equals(renewed)) {
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
                                    review.identity().jobId(),
                                    review.workspaceId() + 1000000,
                                    0,
                                    review.identity().runId()),
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
            assertThat(sendQueue.current(review.workspaceId(), id)).isEmpty();
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isNull();
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            RuntimeJobTestData.leaseUntil(jdbc, job, Instant.now().minusSeconds(1));
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
            assertThat(sendQueue.current(owner.workspaceId(), id)).isEmpty();
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
            assertThat(sendQueue.current(review.workspaceId(), feedbackId)).hasValueSatisfying(send -> {
                assertThat(send.getAction()).isEqualTo(FeedbackSendAction.POST);
                assertThat(send.getState()).isEqualTo(FeedbackSendState.PENDING);
            });
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getPublicReviewDigest())
                    .isEqualTo(prepared.packageDigest());

            ActionTestSupport.drain(executor, jdbc);

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
            assertThat(sendQueue.current(review.workspaceId(), feedbackId))
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
            // Every retry was lost before this attempt, which resumes the composition as the last one.
            for (int lost = 0; lost < agentRuns.capacity().reviewRetries(); lost++) RuntimeJobTestData.retry(jdbc, job);
            // The final composition window has expired, so it withholds without a model call.
            var sessions = new CompositionSessions(
                    admission,
                    continuation,
                    assignments,
                    descriptors,
                    reviewProperties,
                    agentRuns.capacity(),
                    meters,
                    mapper,
                    mock(FeedbackRepair.class),
                    mock(CompositionToolTrace.class));
            // The sandbox's composer loop finds no time left before its first call, so it closes every destination
            // with the deadline; on the last attempt the server withholds them instead of handing them on.
            var identity = new ResultAdmissionService.AdmissionIdentity(
                    job.getId(), job.getWorkspaceId(), job.getAttempt(), job.getRunId());
            // The restored folder holds the attempt's person-copy receipt, which the withholdings' reads index.
            var restored = continuation.restore(job);
            try {
                assertThat(sessions.close(
                                job,
                                identity,
                                Map.of(
                                        FeedbackDestination.ON_WORK, CompositionStop.DEADLINE,
                                        FeedbackDestination.PRACTICE_PAGE, CompositionStop.DEADLINE,
                                        FeedbackDestination.CONVERSATION, CompositionStop.DEADLINE)))
                        .isNull();
            } finally {
                restored.close();
            }

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
            Integer completed = transactions.execute(status -> {
                int ended = jobs.transitionStatusOwnedBy(
                        job.getId(), ReviewJobStatus.COMPLETED, Instant.now(), null, job.getRunId(), job.getAttempt());
                RuntimeJobTestData.agree(jdbc, job.getId());
                return ended;
            });
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

            ActionTestSupport.drain(executor, jdbc);

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

            ActionTestSupport.drain(executor, jdbc);

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
            assertThat(sendQueue.current(review.workspaceId(), feedbackId))
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

            ActionTestSupport.drain(executor, jdbc);

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
            ActionTestSupport.drain(executor, jdbc);

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

    /** A running attempt of one ITEM review of a GitHub pull request, leased under its own run, with its capture. */
    @ParameterizedTest(name = "review settled: {0}")
    @ValueSource(booleans = {true, false})
    void shouldResumeTheApprovedCompositionFromItsOriginalInputsWithoutMeasuringAgain(boolean settled) {
        try (var review = claimedReview(FeedbackMode.APPROVE_FIRST)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            UUID feedbackId = admission
                    .publishPackage(review.identity(), composition(response, SUMMARY, context))
                    .pieces()
                    .getFirst()
                    .feedbackId();
            var sealedIds = resultIds(review);
            var originalReport = mapper.createObjectNode().put("sealedReport", "retained");
            RuntimeJobTestData.updateJobs(
                    jdbc,
                    "UPDATE review_job SET status='COMPLETED',completed_at=now(),output=?::jsonb WHERE workspace_id=? AND id=?",
                    originalReport.toString(),
                    review.workspaceId(),
                    review.identity().jobId());
            if (settled) {
                jdbc.update(
                        "UPDATE review SET status='COMPLETE',completed_at=now() WHERE workspace_id=? AND id=?",
                        review.workspaceId(),
                        review.reviewId());
            }
            var ended = jobs.findById(review.identity().jobId()).orElseThrow();
            continuation.forget(ended);
            assertThat(compositionBases.findByWorkspaceIdAndJobId(review.workspaceId(), ended.getId()))
                    .isPresent();
            assertThat(jobs.findById(ended.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
            Instant resumeFrom = Instant.now();
            decisions.decide(
                    review.workspaceId(),
                    feedbackId,
                    review.developerId(),
                    new FeedbackDecisionInput(
                            FeedbackDecisionAction.APPROVE,
                            null,
                            null,
                            digests.current(review.workspaceId(), feedbackId)));
            // The approved post's readback resumes the composition.
            ActionTestSupport.drain(executor, jdbc);
            var resumed = jobs.findById(ended.getId()).orElseThrow();
            assertThat(ReviewRunKind.compositionWorkDeadline(Objects.requireNonNull(agentRuns
                            .find(new AgentRunRef(resumed.getRunId(), review.workspaceId()))
                            .orElseThrow()
                            .getDeadline())))
                    .isBetween(
                            resumeFrom
                                    .plus(reviewProperties.compositionTimeout())
                                    .minusMillis(1),
                            Instant.now().plus(reviewProperties.compositionTimeout()));
            assertThat(resumed.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM review WHERE workspace_id=? AND id=?",
                            String.class,
                            review.workspaceId(),
                            review.reviewId()))
                    .isEqualTo(settled ? "COMPLETE" : "QUEUED");
            assertThat(resumed.getAttempt()).isEqualTo(ended.getAttempt() + 1);
            assertThat(resumed.getOutput()).isEqualTo(originalReport);
            assertThat(resultIds(review)).containsExactlyElementsOf(sealedIds);
            var canCompose =
                    transactions.execute(status -> reviewStart.compose(review.workspaceId(), review.reviewId()));
            assertThat(canCompose).isTrue();
            assertThat(continuation.retainedInputs(resumed)).containsKey(RECORD);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM review WHERE workspace_id=? AND id=?",
                            String.class,
                            review.workspaceId(),
                            review.reviewId()))
                    .isEqualTo(settled ? "COMPLETE" : "RUNNING");
            RuntimeJobTestData.updateJobs(
                    jdbc,
                    "UPDATE review_job SET status='COMPLETED',completed_at=now() WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    ended.getId());
            var expired = Timestamp.from(Instant.now().minusSeconds(1));
            jdbc.update(
                    "UPDATE review_composition_basis SET expires_at=? WHERE workspace_id=? AND job_id=?",
                    expired,
                    review.workspaceId(),
                    ended.getId());
            var expiredResume = transactions.execute(
                    status -> reviewRuns.resumeComposition(review.workspaceId(), review.reviewId()));
            assertThat(expiredResume).isFalse();
        }
    }

    @Test
    void shouldResumeCompletedReadbackWhenTheAfterCommitStageDidNotRun() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var admitted = admitStaged(review.identity(), request(review));
            var publicationContext =
                    admission.publicFeedbackHistory(review.identity()).publicationContext();
            UUID feedbackId = admission
                    .publishPackage(review.identity(), composition(admitted, SUMMARY, publicationContext))
                    .pieces()
                    .getFirst()
                    .feedbackId();
            ActionTestSupport.drain(executor, jdbc);
            assertThat(feedback.findByIdAndWorkspaceId(feedbackId, review.workspaceId())
                            .orElseThrow()
                            .getComposition()
                            .path("readback")
                            .isArray())
                    .isTrue();
            RuntimeJobTestData.updateJobs(
                    jdbc,
                    "UPDATE review_job SET status='COMPLETED',completed_at=now() WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    review.identity().jobId());
            var ended = jobs.findById(review.identity().jobId()).orElseThrow();
            assertThat(compositionBases.findByWorkspaceIdAndJobId(review.workspaceId(), ended.getId()))
                    .isPresent();
            String readAt = Instant.now().toString();

            AfterCommitCheckpoint.hold(
                    context.getBean(PlatformTransactionManager.class),
                    () -> {
                        jdbc.update(
                                "UPDATE feedback SET composition=jsonb_set(composition,'{readAt}',to_jsonb(?::text))"
                                        + " WHERE workspace_id=? AND id=?",
                                readAt,
                                review.workspaceId(),
                                feedbackId);
                        events.publishEvent(new FeedbackReadbackObservedEvent(review.workspaceId(), review.reviewId()));
                    },
                    () -> {
                        assertThat(jdbc.queryForObject(
                                        "SELECT composition->>'readAt' FROM feedback WHERE workspace_id=? AND id=?",
                                        String.class,
                                        review.workspaceId(),
                                        feedbackId))
                                .isEqualTo(readAt);
                        assertThat(jobs.findById(ended.getId()).orElseThrow().getStatus())
                                .isEqualTo(ReviewJobStatus.COMPLETED);
                        var publications = jdbc.queryForList(
                                "SELECT id FROM event_publication WHERE event_type=?"
                                        + " AND serialized_event::jsonb->>'reviewId'=?",
                                UUID.class,
                                FeedbackReadbackObservedEvent.class.getName(),
                                review.reviewId().toString());
                        assertThat(publications).hasSize(1);
                        UUID publicationId = publications.getFirst();
                        // The staleness monitor marks this committed publication failed after its process stops.
                        jdbc.update(
                                "UPDATE event_publication SET status='FAILED',publication_date=? WHERE id=?",
                                Timestamp.from(Instant.EPOCH),
                                publicationId);
                        failedPublications.resubmit(ResubmissionOptions.defaults()
                                .withBatchSize(1)
                                .withFilter(publication ->
                                        publication.getIdentifier().equals(publicationId)));
                        var resumed = jobs.findById(ended.getId()).orElseThrow();
                        assertThat(resumed.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                        assertThat(resumed.getAttempt()).isEqualTo(ended.getAttempt() + 1);
                        assertThat(jdbc.queryForList("SELECT id FROM event_publication WHERE id=?", publicationId))
                                .isEmpty();
                    });

            // Releasing the held original dispatch delivers the same wake again; it cannot queue a second continuation.
            assertThat(jobs.findById(ended.getId()).orElseThrow().getAttempt()).isEqualTo(ended.getAttempt() + 1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTEXT_WINDOW", "PROVIDER", "DEADLINE", "STEP_LIMIT"})
    void shouldKeepAStoppedRepairTerminalAfterDelivery(String reason) {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            UUID id = admission
                    .publishPackage(review.identity(), composition(response, SUMMARY, context))
                    .pieces()
                    .getFirst()
                    .feedbackId();
            ActionTestSupport.drain(executor, jdbc);
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(FeedbackState.DELIVERED);
            repairs.stop(review.workspaceId(), review.reviewId(), reason);
            RuntimeJobTestData.updateJobs(
                    jdbc,
                    "UPDATE review_job SET status='COMPLETED',completed_at=now() WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    review.identity().jobId());
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getComposition()
                            .path("stop")
                            .asString())
                    .isEqualTo(reason);
            assertThat(jobs.findById(review.identity().jobId()).orElseThrow().getStatus())
                    .isEqualTo(ReviewJobStatus.COMPLETED);
            var resumed = transactions.execute(
                    status -> reviewRuns.resumeComposition(review.workspaceId(), review.reviewId()));
            assertThat(compositionBases.findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId()))
                    .isPresent();
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getComposition()
                            .path("readback")
                            .isArray())
                    .isTrue();
            assertThat(resumed).isFalse();
            continuation.forget(jobs.findById(review.identity().jobId()).orElseThrow());
        }
    }

    @Test
    void shouldNotResumePostedFeedbackPastTheExistingRetryAllowance() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var context = admission.publicFeedbackHistory(review.identity()).publicationContext();
            UUID id = admission
                    .publishPackage(review.identity(), composition(response, SUMMARY, context))
                    .pieces()
                    .getFirst()
                    .feedbackId();
            ActionTestSupport.drain(executor, jdbc);
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getState())
                    .isEqualTo(FeedbackState.DELIVERED);
            transactions.executeWithoutResult(status -> {
                // Requeued with every retry spent, then completed: past the allowance a resume would need.
                jdbc.update(
                        "UPDATE agent_run SET state='QUEUED', leased_until=NULL, attempt=? WHERE id=?",
                        agentRuns.capacity().reviewRetries() + 1,
                        review.identity().runId());
                RuntimeJobTestData.updateJobs(
                        jdbc,
                        "UPDATE review_job SET status='COMPLETED',completed_at=now() WHERE workspace_id=? AND id=?",
                        review.workspaceId(),
                        review.identity().jobId());
            });
            var ended = jobs.findById(review.identity().jobId()).orElseThrow();
            assertThat(compositionBases.findByWorkspaceIdAndJobId(review.workspaceId(), ended.getId()))
                    .isPresent();
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getComposition()
                            .path("readback")
                            .isArray())
                    .isTrue();
            var resumed = transactions.execute(
                    status -> reviewRuns.resumeComposition(review.workspaceId(), review.reviewId()));
            assertThat(resumed).isFalse();
            continuation.forget(ended);
            assertThat(feedback.findByIdAndWorkspaceId(id, review.workspaceId())
                            .orElseThrow()
                            .getComposition()
                            .path("stop")
                            .asString())
                    .isEqualTo("RETRIES_EXHAUSTED");
            assertThat(jobs.findById(ended.getId()).orElseThrow()).satisfies(row -> {
                assertThat(row.getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
                assertThat(row.getAttempt()).isEqualTo(ended.getAttempt());
            });
        }
    }

    @Test
    void shouldKeepExactToolArgumentsAndOutcomesUntilTheOriginalCapsuleExpiry() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var original = compositionBases
                    .findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId())
                    .orElseThrow();
            var arguments = mapper.createObjectNode().put("summary", "Keep the failure cause.");
            toolTrace.record(
                    review.identity(),
                    "checked-1",
                    "check_package",
                    arguments,
                    "Placement accepted.",
                    CompositionToolTrace.Decision.none(FeedbackDestination.ON_WORK));
            RuntimeJobTestData.updateJobs(
                    jdbc,
                    "UPDATE review_job SET status='COMPLETED',completed_at=now() WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    review.identity().jobId());
            continuation.forget(jobs.findById(review.identity().jobId()).orElseThrow());
            var retained = compositionBases
                    .findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId())
                    .orElseThrow();
            assertThat(retained.getExpiresAt()).isEqualTo(original.getExpiresAt());
            assertThat(retained.getBasisDigest()).isEqualTo(original.getBasisDigest());
            assertThat(retained.getPayload().path("tools")).hasSize(1);
            assertThat(retained.getPayload().path("tools").path(0).path("arguments"))
                    .isEqualTo(arguments);
            assertThat(retained.getPayload()
                            .path("tools")
                            .path(0)
                            .path("outcome")
                            .asString())
                    .isEqualTo("Placement accepted.");
        }
    }

    @Test
    void shouldRefuseAnotherToolTraceAfterItsCopiedEvidenceWasRevoked() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            toolTrace.record(
                    review.identity(),
                    "checked-1",
                    "check_package",
                    mapper.createObjectNode(),
                    "Placement accepted.",
                    CompositionToolTrace.Decision.none(FeedbackDestination.ON_WORK));
            var original = compositionBases
                    .findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId())
                    .orElseThrow();
            jdbc.update(
                    "UPDATE person_evidence_copy SET state='ERASED',payload='{}'::jsonb WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    original.getCopyId());
            assertThatThrownBy(() -> toolTrace.record(
                            review.identity(),
                            "checked-2",
                            "check_package",
                            mapper.createObjectNode(),
                            "New text.",
                            CompositionToolTrace.Decision.none(FeedbackDestination.ON_WORK)))
                    .isInstanceOfSatisfying(
                            ReviewCompositionContinuation.InputsUnavailableException.class,
                            refused -> assertThat(refused.erased()).isTrue());
            var retained = compositionBases
                    .findByWorkspaceIdAndJobId(
                            review.workspaceId(), review.identity().jobId())
                    .orElseThrow();
            assertThat(retained.getPayload().path("tools")).hasSize(1);
        }
    }

    @Test
    void shouldPreserveHumanThreadControlInAnotherWorkspaceThatIndexesTheSameProviderWork() {
        try (var observing = claimedReview(FeedbackMode.AUTOMATIC)) {
            var owner = data.scenario(DefinitionTestFixtures.definition());
            var now = Instant.now();
            var indexed = new Item();
            indexed.setWorkspaceId(owner.workspace().getId());
            indexed.setKind(ScmItemEvents.PULL_REQUEST);
            indexed.setSourceId(observing.pullRequestId());
            indexed.setCreatedAt(now);
            indexed = items.saveAndFlush(indexed);
            UUID ownerReview = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at) "
                            + "VALUES(?,?,?,'ITEM',?,'REQUEST','RUNNING',now(),now())",
                    ownerReview,
                    owner.workspace().getId(),
                    indexed.getId(),
                    observing.itemVersion());
            var row = feedback.saveAndFlush(Feedback.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(owner.workspace().getId())
                    .reviewId(ownerReview)
                    .itemId(indexed.getId())
                    .developerId(Objects.requireNonNull(owner.account().getId()))
                    .destination(FeedbackDestination.ON_WORK)
                    .state(FeedbackState.DELIVERED)
                    .createdAt(now)
                    .deliveredAt(now)
                    .build());
            String threadRef = "PRRT_" + UUID.randomUUID();
            UUID copyId = FeedbackIds.copy(row.getId(), FeedbackCopyKind.LINE_NOTE, 0);
            var receipt = copies.saveAndFlush(FeedbackCopy.builder()
                    .id(copyId)
                    .workspaceId(owner.workspace().getId())
                    .feedbackId(row.getId())
                    .kind(FeedbackCopyKind.LINE_NOTE)
                    .position(0)
                    .body("Keep the failure cause.")
                    .path("src/A.java")
                    .startLine(1)
                    .endLine(1)
                    .side(CitationSide.NEW)
                    .state(FeedbackCopyState.POSTED)
                    .writeKey(copyId.toString())
                    .writeMarker(FeedbackIds.writeMarker(copyId))
                    .writeStartedAt(now)
                    .postedAt(now)
                    .externalRef("PRRC_" + UUID.randomUUID())
                    .threadRef(threadRef)
                    .createdAt(now)
                    .build());
            Long threadId = jdbc.queryForObject(
                    "INSERT INTO pull_request_review_thread(native_id,provider_id,pull_request_id,node_id,state) "
                            + "SELECT ?,provider_id,id,?,'UNRESOLVED' FROM issue WHERE id=? RETURNING id",
                    Long.class,
                    ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE),
                    threadRef,
                    observing.pullRequestId());
            var event = new ScmDomainEvent.ReviewThreadUnresolved(
                    new ScmEventPayload.ReviewThreadData(
                            Objects.requireNonNull(threadId),
                            PullRequestReviewThread.State.UNRESOLVED,
                            "src/A.java",
                            1,
                            null,
                            observing.pullRequestId()),
                    new EventContext(
                            UUID.randomUUID(),
                            now,
                            observing.workspaceId(),
                            null,
                            DataSource.WEBHOOK,
                            "unresolved",
                            "synthetic-thread-event",
                            IdentityProviderType.GITHUB));
            transactions.executeWithoutResult(status -> events.publishEvent(event));
            assertThat(feedback.findByIdAndWorkspaceId(
                                    row.getId(), owner.workspace().getId())
                            .orElseThrow()
                            .getComposition()
                            .path("humanControlled")
                            .asBoolean())
                    .isTrue();
            assertThat(copies.findById(receipt.getId()).orElseThrow().getThreadRef())
                    .isEqualTo(threadRef);
        }
    }

    @Test
    void shouldPublishOnlyADraftThatTheServerCheckedWhenTheComposerNamesItsDraftId() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            var sessions = sessions();
            var deadline = Instant.now().plusSeconds(2);

            var session = (CompositionSessions.Session)
                    sessions.next(job, review.identity(), Set.of()).orElseThrow();
            assertThat(session.destination()).isEqualTo(FeedbackDestination.ON_WORK);
            assertThat(session.tools())
                    .extracting(CompositionSessions.Tool::name)
                    .containsExactly("check_package", "publish_package", "edit_own");
            assertThat(session.input()).contains(QUOTE);

            var result = response.admitted().getFirst().resultId();
            var arguments = mapper.readTree("""
                    {"decisions":[{"resultId":"%1$s","disposition":"RAISE"}],
                     "summary":{"basedOn":["%1$s"],"body":"%2$s"}}
                    """.formatted(result, SUMMARY));
            var checked =
                    sessions.tool(job, review.identity(), toolCall(session, "check_package", arguments, deadline));
            String draftId = mapper.readTree(checked.text()).path("draftId").asString();

            // An ID that no check of this attempt returned publishes nothing.
            var unchecked = sessions.tool(
                    job, review.identity(), toolCall(session, "publish_package", draft("0".repeat(64)), deadline));
            assertThat(unchecked.published()).isFalse();
            assertThat(unchecked.text()).startsWith("Unknown draftId");
            assertThat(assignments.publicReviewed(job)).isFalse();

            var published = sessions.tool(
                    job, review.identity(), toolCall(session, "publish_package", draft(draftId), deadline));
            assertThat(published.published()).as(published.text()).isTrue();
            assertThat(admission.preparedReview(job))
                    .hasValueSatisfying(
                            prepared -> assertThat(prepared.summary()).isEqualTo(SUMMARY));

            // The same draft again is refused: the review on the work is already accepted.
            var again = sessions.tool(
                    job, review.identity(), toolCall(session, "publish_package", draft(draftId), deadline));
            assertThat(again.published()).isFalse();
            assertThat(again.text()).contains("already published");

            var traced = new ArrayList<String>();
            compositionBases
                    .findByWorkspaceIdAndJobId(review.workspaceId(), job.getId())
                    .orElseThrow()
                    .getPayload()
                    .path("tools")
                    .forEach(entry -> traced.add(entry.path("tool").asString() + ":"
                            + entry.path("published").asBoolean(false)));
            assertThat(traced)
                    .containsExactly(
                            "check_package:false",
                            "publish_package:false",
                            "publish_package:true",
                            "publish_package:false");
            // The review on the work is decided, so closing records nothing for it and hands nothing on.
            assertThat(sessions.close(job, review.identity(), Map.of())).isNull();
        }
    }

    @Test
    void shouldComposeThroughTheSandboxGatewayAndCompleteTheReviewWhenTheComposeRunEnds() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var response = admitStaged(review.identity(), request(review));
            review.inputs().close();
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            UUID composeRun = composeRun(job);
            UUID result = response.admitted().getFirst().resultId();
            var calls = new AtomicInteger();
            var custody = new ReviewAttemptCustody(agentRuns);

            try (var gateway = new ComposeRunGateway(context, composingPolicy(true), custody, request -> {
                calls.incrementAndGet();
                return composerAnswer(request, result);
            })) {
                String token = gateway.sandbox(review.workspaceId(), subjectOf(composeRun));
                assertThat(gateway.drive(token)).isEqualTo("{\"ended\":204}");
                assertThat(gateway.drive(token)).isEqualTo("{\"idle\":true}");
            }

            var completed = jobs.findById(job.getId()).orElseThrow();
            assertThat(completed.getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
            assertThat(completed.getProcessingStatus()).isEqualTo(ProcessingStatus.PENDING);
            assertThat(runState(composeRun)).isEqualTo("ENDED");
            assertThat(calls).hasPositiveValue();
            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .filteredOn(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                    .singleElement()
                    .satisfies(onWork -> {
                        assertThat(onWork.getState()).isEqualTo(FeedbackState.DELIVERED);
                        assertThat(onWork.getBody()).contains(SUMMARY);
                    });
            assertThat(github.threads().on(ScriptedGitHub.pullRequest(review.repository(), review.pullRequestNumber())))
                    .singleElement()
                    .satisfies(comment -> assertThat(comment.body()).contains(PostedText.of(SUMMARY)));
            // The run's end released the inputs it held: holding the attempt again prepares anew.
            var released = new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null);
            var attempt = new ResultAdmissionService.AdmissionIdentity(
                    job.getId(), review.workspaceId(), completed.getAttempt(), composeRun);
            assertThat(custody.hold(attempt, () -> released)).isSameAs(released);
        }
    }

    @Test
    void shouldRetryCompositionOnANewRunWithAFreshDeadlineAndTheSameAttemptBudget() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var sealed = resultIds(review);
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            for (int lost = 0; lost < agentRuns.capacity().reviewRetries() - 1; lost++)
                RuntimeJobTestData.retry(jdbc, job);
            job = jobs.findById(job.getId()).orElseThrow();
            UUID composing = composeRun(job);
            var claimed = claimAgain(job);
            var fence = RuntimeJobTestData.fence(jdbc, claimed);
            UUID jobId = job.getId();

            Boolean handedOn = transactions.execute(status -> {
                // The gateway's end first loads the composing run in this same persistence context.
                admission.composing(fence);
                return reviewRuns.retryComposition(jobId, fence);
            });
            assertThat(handedOn).isTrue();

            var retry = jobs.findById(job.getId()).orElseThrow();
            assertThat(retry.getRunId()).isNotEqualTo(composing);
            assertThat(retry.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(jdbc.queryForObject(
                            "SELECT status FROM review WHERE workspace_id=? AND id=?",
                            String.class,
                            review.workspaceId(),
                            review.reviewId()))
                    .isEqualTo("QUEUED");
            assertThat(retry.spentAttempts()).isEqualTo(claimed.spentAttempts() + 1);
            assertThat(retry.getAttempt()).isEqualTo(claimed.getAttempt() + 1);
            var next = agentRuns
                    .find(new AgentRunRef(retry.getRunId(), review.workspaceId()))
                    .orElseThrow();
            var workEnds = ReviewRunKind.compositionWorkDeadline(Objects.requireNonNull(next.getDeadline()));
            assertThat(workEnds)
                    .isAfter(next.getAvailableAt()
                            .plus(reviewProperties.compositionTimeout().dividedBy(2)));
            assertThat(next.getAvailableAt()).isAfter(next.getCreatedAt());
            assertThat(agentRuns.find(new AgentRunRef(composing, review.workspaceId())))
                    .isEmpty();
            assertThat(resultIds(review)).containsExactlyElementsOf(sealed);
            Boolean stale = transactions.execute(status -> reviewRuns.retryComposition(jobId, fence));
            assertThat(stale).isFalse();
        }
    }

    @Test
    void shouldContinueLostCompositionWithoutResettingItsRetryCeilingOrMovingTheDomainOnRollback() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var sealed = resultIds(review);
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            UUID jobId = job.getId();
            for (int spent = 0; spent < agentRuns.capacity().reviewRetries() - 1; spent++)
                RuntimeJobTestData.retry(jdbc, job);
            job = jobs.findById(jobId).orElseThrow();
            composeRun(job);
            var current = claimAgain(job);
            var original = RuntimeJobTestData.fence(jdbc, current);
            jdbc.update(
                    "UPDATE agent_run SET leased_until = now() - interval '1 second' WHERE id = ?", original.runId());
            assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                        reviewRuns.lock(original.run());
                        reviewRuns.continueExpiredComposition(original);
                        throw new IllegalStateException("domain transaction refused");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("domain transaction refused");
            assertThat(jobs.findById(jobId).orElseThrow().getRunId()).isEqualTo(original.runId());
            assertThat(runState(original.runId())).isEqualTo("LEASED");
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM agent_run WHERE workspace_id = ? AND concurrency_key = ?",
                            Long.class,
                            review.workspaceId(),
                            subjectOf(original.runId())))
                    .isEqualTo(1);
            int ceiling = context.getBean(ReviewComposeRunKind.class).retries();
            for (int spent = current.spentAttempts(); spent < ceiling; spent++) {
                UUID previous = current.getRunId();
                int previousAttempt = current.getAttempt();
                jdbc.update("UPDATE agent_run SET leased_until = now() - interval '1 second' WHERE id = ?", previous);
                reaper.reap();
                var queued = jobs.findById(jobId).orElseThrow();
                assertThat(queued.getRunId()).isNotEqualTo(previous);
                assertThat(queued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
                assertThat(jdbc.queryForObject(
                                "SELECT status FROM review WHERE workspace_id=? AND id=?",
                                String.class,
                                review.workspaceId(),
                                review.reviewId()))
                        .isEqualTo("QUEUED");
                assertThat(queued.spentAttempts()).isEqualTo(spent + 1);
                assertThat(queued.getAttempt()).isEqualTo(previousAttempt + 1);
                var next = agentRuns
                        .find(new AgentRunRef(queued.getRunId(), review.workspaceId()))
                        .orElseThrow();
                assertThat(ReviewRunKind.compositionWorkDeadline(Objects.requireNonNull(next.getDeadline())))
                        .isEqualTo(next.getAvailableAt().plus(reviewProperties.compositionTimeout()));
                assertThat(next.getAvailableAt()).isAfter(next.getCreatedAt());
                assertThat(agentRuns.isCurrent(
                                new AgentRunRef(previous, review.workspaceId()), ReviewRunKind.epoch(previousAttempt)))
                        .isFalse();
                jdbc.update("UPDATE agent_run SET available_at = now() WHERE id = ?", queued.getRunId());
                current = claimAgain(queued);
            }
            UUID last = current.getRunId();
            jdbc.update("UPDATE agent_run SET leased_until = now() - interval '1 second' WHERE id = ?", last);
            reaper.reap();
            reaper.reap();
            var ended = jobs.findById(jobId).orElseThrow();
            assertThat(ended.getRunId()).isEqualTo(last);
            assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
            assertThat(runState(last)).isEqualTo("ENDED");
            assertThat(Objects.requireNonNull(ended.getOutput()).path("outcome").asString())
                    .isEqualTo("COMPOSITION_UNCONFIRMED");
            assertThat(resultIds(review)).containsExactlyElementsOf(sealed);
        }
    }

    @Test
    void shouldRecordDeadlineWithholdingsBeforeTheDriverAbortsItsFinalComposition() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            for (int lost = 0; lost < agentRuns.capacity().reviewRetries(); lost++) RuntimeJobTestData.retry(jdbc, job);
            job = jobs.findById(job.getId()).orElseThrow();
            review.inputs().close();
            UUID composing;
            var calls = new AtomicInteger();
            double before =
                    count(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", "deadline", "outcome", "withheld");
            try (var gateway = new ComposeRunGateway(
                    context, composingPolicy(true), new ReviewAttemptCustody(agentRuns), request -> {
                        calls.incrementAndGet();
                        return mapper.createObjectNode();
                    })) {
                composing = queuedComposition(
                        job, ReviewRunKind.compositionRunDeadline(Instant.now().minusSeconds(1)));
                assertThat(gateway.drive(gateway.sandbox(review.workspaceId(), subjectOf(composing))))
                        .isEqualTo("{\"ended\":204}");
            }
            assertThat(calls).hasValue(0);
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
            assertThat(runState(composing)).isEqualTo("ENDED");
            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .isNotEmpty()
                    .allSatisfy(row -> {
                        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
                        assertThat(row.getStopReason()).isEqualTo(FeedbackStopReason.NOT_COMPOSED);
                    });
            assertThat(count(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", "deadline", "outcome", "withheld"))
                    .isGreaterThan(before);
        }
    }

    @Test
    void shouldKeepAdmittedResultsAndReportUnconfirmedCompositionWhenItsQueuedRunExpires() {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            var sealed = resultIds(review);
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            double before =
                    count(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", "deadline", "outcome", "unrecorded");
            UUID queued = queuedComposition(job, Instant.EPOCH);

            reaper.reap();
            completion.tick();

            assertThat(runState(queued)).isEqualTo("ENDED");
            var ended = jobs.findById(job.getId()).orElseThrow();
            assertThat(Objects.requireNonNull(ended.getOutput()).path("outcome").asString())
                    .isEqualTo("COMPOSITION_UNCONFIRMED");
            assertThat(resultIds(review)).containsExactlyElementsOf(sealed);
            var recorded = jdbc.queryForMap(
                    "SELECT status, stop_reason FROM review WHERE workspace_id=? AND id=?",
                    review.workspaceId(),
                    review.reviewId());
            assertThat(recorded)
                    .containsEntry("status", "PARTIAL")
                    .containsEntry("stop_reason", "COMPOSITION_UNCONFIRMED");
            assertThat(count(AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", "deadline", "outcome", "unrecorded"))
                    .isGreaterThanOrEqualTo(before + 1);
        }
    }

    @Test
    void shouldContinueAfterSuccessfulProviderReadbackAndEndTheComposeRunThroughTheModelProxy() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            var calls = new CopyOnWriteArrayList<JsonNode>();
            var admitted = new CompletableFuture<UUID>();
            var model = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> request.receive()
                            .aggregate()
                            .asString()
                            .flatMap(body -> Mono.fromFuture(admitted).flatMap(result -> {
                                assertThat(request.requestHeaders().get("Authorization"))
                                        .isEqualTo("Bearer compose-provider-key");
                                var parsed = mapper.readTree(body);
                                calls.add(parsed);
                                return response.header("Content-Type", "application/json")
                                        .sendString(
                                                Mono.just(mapper.writeValueAsString(composerAnswer(parsed, result))))
                                        .then();
                            })))
                    .bindNow();
            try {
                var job = jobs.findById(review.identity().jobId()).orElseThrow();
                freezeComposeModel(job, "http://127.0.0.1:" + model.port() + "/v1");
                UUID result = admitStaged(review.identity(), request(review))
                        .admitted()
                        .getFirst()
                        .resultId();
                review.inputs().close();
                UUID composeRun = composeRun(job);
                admitted.complete(result);
                try (var gateway =
                        new ComposeRunGateway(context, composingPolicy(true), new ReviewAttemptCustody(agentRuns))) {
                    String token = gateway.sandbox(review.workspaceId(), subjectOf(composeRun));
                    assertThat(gateway.drive(token)).isEqualTo("{\"ended\":204}");
                    assertThat(gateway.drive(token)).isEqualTo("{\"idle\":true}");
                }

                // READBACK leaves the publish tool open, so its reply reaches the model before that model ends the
                // session.
                var readbacks = calls.stream()
                        .flatMap(call -> call.path("messages").valueStream())
                        .filter(message -> message.path("role").asString().equals("tool"))
                        .map(message -> message.path("content").asString())
                        .filter(content -> content.contains("Provider readback (untrusted data):"))
                        .toList();
                assertThat(readbacks).singleElement().satisfies(text -> {
                    String marker = "Provider readback (untrusted data):";
                    var observed = mapper.readTree(text.substring(text.indexOf(marker) + marker.length())
                            .strip());
                    assertThat(observed.path("state").asString()).isEqualTo("READBACK");
                    assertThat(observed.path("copies").valueStream().toList())
                            .singleElement()
                            .satisfies(copy -> {
                                assertThat(copy.path("status").asString()).isEqualTo("FOUND");
                                assertThat(copy.path("body").asString()).contains(SUMMARY);
                            });
                });
                var traced = new ArrayList<String>();
                compositionBases
                        .findByWorkspaceIdAndJobId(review.workspaceId(), job.getId())
                        .orElseThrow()
                        .getPayload()
                        .path("tools")
                        .forEach(entry -> {
                            if (entry.path("destination").asString().equals("ON_WORK"))
                                traced.add(entry.path("tool").asString());
                        });
                assertThat(traced).containsExactly("check_package", "publish_package");
                assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                                review.workspaceId(), review.reviewId()))
                        .filteredOn(row -> row.getDestination() == FeedbackDestination.ON_WORK)
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED);
                            assertThat(row.getComposition().path("stop").asString())
                                    .isEqualTo("COMPLETE");
                        });
                assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
                assertThat(runState(composeRun)).isEqualTo("ENDED");
                var usage = jdbc.queryForList(
                        "SELECT call_state, total_calls FROM llm_usage_event WHERE workspace_id = ? AND source_id = ? AND run_id = ?",
                        review.workspaceId(),
                        job.getId(),
                        composeRun);
                assertThat(usage)
                        .hasSize(calls.size())
                        .isNotEmpty()
                        .allSatisfy(row -> assertThat(row)
                                .containsEntry("call_state", "ENDED")
                                .containsEntry("total_calls", 1));
                var created = agentRuns
                        .find(new AgentRunRef(composeRun, review.workspaceId()))
                        .orElseThrow();
                assertThat(created.getAvailableAt()).isEqualTo(created.getCreatedAt());
                assertThat(ReviewRunKind.compositionWorkDeadline(Objects.requireNonNull(created.getDeadline())))
                        .isEqualTo(created.getAvailableAt().plus(reviewProperties.compositionTimeout()));
            } finally {
                model.disposeNow();
            }
        }
    }

    private void freezeComposeModel(ReviewJob job, String baseUrl) {
        var connection = LlmCatalogTestFixtures.connection("compose-" + UUID.randomUUID());
        connection.setBaseUrl(baseUrl);
        connection.setAuthMode(LlmAuthMode.BEARER);
        connection.setApiKey("compose-provider-key");
        connection = context.getBean(LlmConnectionRepository.class).save(connection);
        var model = context.getBean(LlmModelRepository.class)
                .save(LlmCatalogTestFixtures.model(connection, "compose-" + UUID.randomUUID(), "contract-model"));
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
        job.setConfigSnapshot(new ConfigSnapshot(
                        snapshot.schemaVersion(),
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                baseUrl,
                                "contract-model",
                                UsageFundingSource.INSTANCE,
                                connection.getId(),
                                model.getId(),
                                job.getWorkspaceId(),
                                DataHandlingTier.UNDECLARED,
                                null,
                                null,
                                false),
                        snapshot.contextWindow(),
                        snapshot.maxOutputTokens(),
                        snapshot.timeoutSeconds())
                .toJson(mapper));
        jobs.saveAndFlush(job);
    }

    @Test
    void shouldWithholdWithoutAModelCallWhenTheComposeRunsClaimFindsAIDeclined() throws Exception {
        try (var review = claimedReview(FeedbackMode.AUTOMATIC)) {
            admitStaged(review.identity(), request(review));
            review.inputs().close();
            var job = jobs.findById(review.identity().jobId()).orElseThrow();
            UUID composeRun = composeRun(job);
            var calls = new AtomicInteger();

            try (var gateway = new ComposeRunGateway(
                    context, composingPolicy(false), new ReviewAttemptCustody(agentRuns), request -> {
                        calls.incrementAndGet();
                        return mapper.createObjectNode();
                    })) {
                String token = gateway.sandbox(review.workspaceId(), subjectOf(composeRun));
                assertThat(gateway.drive(token)).isEqualTo("{\"ended\":204}");
            }

            assertThat(calls).hasValue(0);
            assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
            assertThat(feedback.findByWorkspaceIdAndReviewIdOrderByCreatedAtAscIdAsc(
                            review.workspaceId(), review.reviewId()))
                    .isNotEmpty()
                    .allSatisfy(withheld -> {
                        assertThat(withheld.getState()).isEqualTo(FeedbackState.WITHHELD);
                        assertThat(withheld.getStopReason()).isEqualTo(FeedbackStopReason.NO_AI);
                    })
                    .extracting(Feedback::getDestination)
                    .contains(FeedbackDestination.ON_WORK);
        }
    }

    /* Queues a fixture at a chosen immutable deadline through the run's normal writes. */
    private UUID queuedComposition(ReviewJob job, Instant deadline) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var fence = RuntimeJobTestData.fence(jdbc, job);
            assertThat(agentRuns.end(fence)).isEqualTo(AgentRuns.EndOutcome.ENDED);
            var composing = agentRuns.continueAs(
                    fence.run(),
                    fence.epoch(),
                    AgentRunKind.REVIEW_COMPOSE,
                    Duration.between(
                            Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                                    .toInstant(),
                            deadline));
            assertThat(jobs.queueOnNextRun(job.getId(), fence.runId(), job.getAttempt(), composing.id()))
                    .isOne();
            agentRuns.retire(fence.run());
            return composing.id();
        }));
    }

    private UUID composeRun(ReviewJob job) {
        var fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), ReviewRunKind.epoch(job.getAttempt()));
        return Objects.requireNonNull(transactions.execute(status -> reviewRuns.continueToCompose(job.getId(), fence)))
                .orElseThrow()
                .id();
    }

    /* A developer who allows AI reviews, or not, bound to the model that the review's job froze. */
    private static ReviewMemberAiPolicy composingPolicy(boolean permits) {
        var policy = mock(ReviewMemberAiPolicy.class);
        when(policy.permitsReview(any())).thenReturn(permits);
        when(policy.binding(any())).thenReturn(Optional.of(new WorkspaceAgentBinding()));
        return policy;
    }

    private String subjectOf(UUID run) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id=?", String.class, run));
    }

    private String runState(UUID run) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT state FROM agent_run WHERE id=?", String.class, run));
    }

    /*
     * The model of the review on the work: it checks a package that raises the admitted result with the summary,
     * publishes the draft that the check returned, and has nothing to add after that, nor for the private feedback.
     */
    private ObjectNode composerAnswer(JsonNode request, UUID result) {
        var messages = request.path("messages");
        var checks = new HashSet<String>();
        boolean published = false;
        for (JsonNode message : messages)
            for (JsonNode call : message.path("tool_calls")) {
                String name = call.path("function").path("name").asString();
                if (name.equals("check_package")) checks.add(call.path("id").asString());
                if (name.equals("publish_package")) published = true;
            }
        boolean asked = false;
        String draftId = null;
        for (JsonNode message : messages) {
            if (!message.path("role").asString().equals("tool")) continue;
            asked = true;
            if (!checks.contains(message.path("tool_call_id").asString())) continue;
            String checked = mapper.readTree(message.path("content").asString("{}"))
                    .path("draftId")
                    .asString(null);
            if (checked != null) draftId = checked;
        }
        var message = mapper.createObjectNode().put("role", "assistant");
        if (!asked && messages.toString().contains(QUOTE)) {
            call(message, "check_package", """
                    {"decisions":[{"resultId":"%1$s","disposition":"RAISE"}],
                     "summary":{"basedOn":["%1$s"],"body":"%2$s"}}
                    """.formatted(result, SUMMARY));
        } else if (draftId != null && !published) {
            call(
                    message,
                    "publish_package",
                    mapper.createObjectNode().put("draftId", draftId).toString());
        } else message.put("content", "Done.");
        var answer = mapper.createObjectNode();
        answer.putArray("choices").addObject().put("finish_reason", "stop").set("message", message);
        answer.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5);
        return answer;
    }

    private void call(ObjectNode message, String tool, String arguments) {
        message.putNull("content");
        message.putArray("tool_calls")
                .addObject()
                .put("id", "call-" + UUID.randomUUID())
                .put("type", "function")
                .putObject("function")
                .put("name", tool)
                .put("arguments", arguments);
    }

    private ObjectNode draft(String draftId) {
        return mapper.createObjectNode().put("draftId", draftId);
    }

    private CompositionSessions sessions() {
        return new CompositionSessions(
                admission,
                continuation,
                assignments,
                descriptors,
                reviewProperties,
                agentRuns.capacity(),
                meters,
                mapper,
                repairs,
                toolTrace);
    }

    private static CompositionSessions.ToolCall toolCall(
            CompositionSessions.Session session, String name, JsonNode arguments, Instant deadline) {
        return new CompositionSessions.ToolCall(
                session.destination(),
                "call-" + UUID.randomUUID(),
                name,
                arguments,
                session.publicationContext(),
                deadline);
    }

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
                        600)
                .toJson(mapper));
        jobs.saveAndFlush(job);
        return new ClaimedReview(
                new ResultAdmissionService.AdmissionIdentity(job.getId(), workspace.getId(), 0, job.getRunId()),
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

    /* What the worker's capture recorded: the pull request record, staged and citable as one text. */
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

    /* One summary on the work, composed from the public history named by publicationContext. */
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
