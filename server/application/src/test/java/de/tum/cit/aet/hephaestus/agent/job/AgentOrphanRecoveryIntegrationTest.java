package de.tum.cit.aet.hephaestus.agent.job;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackCompositionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultProofMapper;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Verifies orphan recovery, retry fencing, and claim eligibility against PostgreSQL. */
@DisplayName("Orphan recovery over PostgreSQL Integration")
class AgentOrphanRecoveryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PracticeVersionService runtimePracticeVersions;

    private ReviewJobZombieSweeper sweeper;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private WorkerRegistryRepository workerRegistryRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Autowired
    private LlmConnectionRepository connectionRepository;

    @Autowired
    private LlmModelRepository modelRepository;

    @Autowired
    private LlmModelPriceRepository priceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeVersionRepository practiceVersionRepository;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewCompositionContinuation continuation;

    @Autowired
    private ReviewCompositionBasisRepository compositionBasisRepository;

    private ReviewJobCompletionService completion;

    @Autowired
    private ReviewWriteGuard writeGuard;

    @Autowired
    private ReviewAssignmentStore members;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private ReviewJobLifecycleService lifecycle;

    @Autowired
    private PiResultParser scriptReports;

    @Autowired
    private ScriptRunRepository scriptRuns;

    @Autowired
    private ReviewJobAssignments assignments;

    @Autowired
    private ReviewMethodVerifier methods;

    @Autowired
    private AccountRepository privateAccounts;

    @Autowired
    private IdentityRepository privateIdentities;

    @Autowired
    private MembershipRepository privateMemberships;

    @Autowired
    private ProblemRepository privateProblems;

    @Autowired
    private ResultProofMapper privateProof;

    @Autowired
    private FeedbackCompositionService privateComposition;

    @Autowired
    private ConnectionRepository privateConnections;

    @Autowired
    private RepositoryRepository privateRepositories;

    @Autowired
    private RepositoryToMonitorRepository privateMonitors;

    @Autowired
    private PullRequestRepository privatePullRequests;

    @Autowired
    private ItemRepository privateItems;

    @Autowired
    private ScmItemService privateItemSource;

    @Autowired
    private ScmItemSourceAccess privateSourceAccess;

    private AgentProperties properties;
    private ReviewJobRecovery recovery;
    private Workspace workspace;
    private LlmModel instanceModel;
    private Practice practice;
    private User developer;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        properties = mock(AgentProperties.class);
        when(properties.maxRetries()).thenReturn(5);
        recovery = new ReviewJobRecovery(
                jobRepository,
                properties,
                objectMapper,
                attemptUsage,
                Optional.of(continuation),
                Optional.of(new PracticeScriptRunRecorder(
                        scriptReports, scriptRuns, meterRegistry, objectMapper, assignments)));
        sweeper = new ReviewJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                objectMapper,
                transactionTemplate,
                attemptUsage,
                meterRegistry,
                new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                recovery);
        completion = new ReviewJobCompletionService(
                jobRepository,
                writeGuard,
                assignments,
                members,
                reviews,
                lifecycle,
                transactionTemplate,
                Clock.systemUTC());
        workspace = workspaceRepository.save(TestEntities.activeWorkspace("orphan-recovery-ws"));

        LlmConnection connection = connectionRepository.save(LlmCatalogTestFixtures.connection("orphan-recovery"));
        instanceModel =
                modelRepository.save(LlmCatalogTestFixtures.model(connection, "orphan-recovery-model", "test-model"));

        LlmModelPrice price = new LlmModelPrice();
        price.setModel(instanceModel);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setNote("Integration-test model has no per-token charge");
        price.setEffectiveFrom(Instant.now().minusSeconds(60));
        priceRepository.save(price);

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setEnabled(true);
        binding.setInstanceModel(instanceModel);
        agentBindingRepository.save(binding);

        practice = runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace,
                "orphan-recovery-practice",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(
                        new ItemKind("scm.pull_request"), "Orphan recovery practice", "The first definition", null),
                null,
                0,
                null));
        practice.setFeedbackMode(FeedbackMode.AUTOMATIC);
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        developer = userRepository.save(TestUserFactory.createUser(100L, "developer", provider));
    }

    @Test
    @DisplayName("a dead worker's RUNNING job is requeued (attempt++) and becomes claimable again")
    void orphanRecoveryRequeuesAndBecomesClaimable() {
        UUID jobId = runningJobOwnedBy("dead-replica", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica");

        sweeper.recoverOrphanedJobs();

        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getWorkerId()).isNull();
        assertThat(requeued.getAttempt()).isEqualTo(1);

        fastForwardAvailableAt(jobId);

        assertThat(eligibleForClaim(jobId)).isTrue();
    }

    @Test
    @DisplayName("a direct claim attempt made WHILE still backed off does not succeed")
    void claimAttemptWhileStillBackedOffDoesNotSucceed() {
        UUID jobId = runningJobOwnedBy("dead-replica-5", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-5");

        sweeper.recoverOrphanedJobs();

        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getAvailableAt())
                .as("the backoff-computed available_at is still in the future")
                .isAfter(Instant.now());

        assertThat(eligibleForClaim(jobId)).isFalse();
        ReviewJob stillQueued = jobRepository.findById(jobId).orElseThrow();
        assertThat(stillQueued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
    }

    @Test
    @DisplayName("a job whose available_at is in the future is not offered as a poll candidate")
    void jobWithFutureAvailableAtIsNotClaimed() {
        UUID jobId = runningJobOwnedBy("dead-replica-4", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-4");

        sweeper.recoverOrphanedJobs();

        // The requeue's backoff puts available_at far enough out that this is not a race with the clock.
        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getAvailableAt()).isAfter(Instant.now());

        assertThat(jobRepository.findQueuedIdsOldestFirst(10))
                .as("a not-yet-eligible QUEUED job must not be offered as a poll candidate")
                .doesNotContain(jobId);
    }

    @Test
    @DisplayName("an orphan already at the retry cap is failed, not requeued again")
    void orphanPastRetryCapIsFailedNotRequeued() {
        UUID jobId = runningJobOwnedBy("dead-replica-2", Instant.now().minus(Duration.ofMinutes(5)), 5);
        registerStaleWorker("dead-replica-2");

        sweeper.recoverOrphanedJobs();

        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("retry limit is reached");
        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).doesNotContain(jobId);
    }

    @Test
    @DisplayName(
            "requeueOrphan is fenced on worker_id — a stale caller cannot steal a job a live sibling has re-claimed")
    void requeueOrphanDoesNotStealAJobReclaimedBySomeoneElse() {
        UUID jobId = runningJobOwnedBy("live-sibling", Instant.now(), 0);

        // @Modifying queries need an active transaction (the sweeper normally provides one via
        // TransactionTemplate); wrap here too.
        int updated =
                transactionTemplate.execute(s -> jobRepository.requeueOrphan(jobId, "dead-replica", 5, Instant.now()));

        assertThat(updated)
                .as("the CAS must not match — the row's worker_id does not match the stale caller's")
                .isZero();
        ReviewJob untouched = jobRepository.findById(jobId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(untouched.getWorkerId()).isEqualTo("live-sibling");
        assertThat(untouched.getAttempt()).isZero();
    }

    @Test
    @DisplayName("requeueOrphan enforces the retry cap in SQL even if a caller forgets to check it first")
    void requeueOrphanRefusesPastTheRetryCapEvenUnchecked() {
        UUID jobId = runningJobOwnedBy("dead-replica-3", Instant.now(), 5);

        int updated = transactionTemplate.execute(
                s -> jobRepository.requeueOrphan(jobId, "dead-replica-3", 5, Instant.now()));

        assertThat(updated).isZero();
        ReviewJob unchanged = jobRepository.findById(jobId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(unchanged.getAttempt()).isEqualTo(5);
    }

    @Test
    @DisplayName("an orphan whose results were admitted fails with them kept, after its practice changed")
    void shouldFailWithTheAdmittedResultsKeptWhenTheOwningWorkerIsLostAfterAdmission() {
        UUID jobId = runningJobOwnedBy("dead-replica-6", Instant.now().minus(Duration.ofMinutes(5)), 0);
        Result observed = admitWithResult(jobId);
        changePracticeDefinition();
        registerStaleWorker("dead-replica-6");
        ReviewJob before = jobRepository.findById(jobId).orElseThrow();

        sweeper.recoverOrphanedJobs();

        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(ResultAdmissionService.INTERRUPTED_AFTER_ADMISSION);
        assertThat(failed.getAttempt()).isZero();
        assertThat(failed.getInput()).isEqualTo(before.getInput());
        assertThat(failed.getCapture()).isEqualTo(before.getCapture());
        Result kept = practiceResultRepository.findById(observed.getId()).orElseThrow();
        assertThat(kept.getReviewId()).isEqualTo(before.getReviewId());
        Long originalVersionId = requireNonNull(observed.getPracticeVersionId());
        assertThat(kept.getPracticeVersionId()).isEqualTo(originalVersionId);
        assertThat(practiceVersionRepository
                        .findById(originalVersionId)
                        .orElseThrow()
                        .getDefinition()
                        .path("criteria")
                        .asString())
                .isEqualTo("The first definition");
        var currentCriteria = transactionTemplate.execute(status -> practiceRepository
                .findById(practice.getId())
                .orElseThrow()
                .getCurrentVersion()
                .getDefinition()
                .path("criteria")
                .asString());
        assertThat(currentCriteria).isEqualTo("The second definition");
        assertThat(kept.getEvidence()).isEqualTo(observed.getEvidence());
        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).doesNotContain(jobId);
    }

    @Test
    @DisplayName("requeueOrphan refuses an admitted attempt in SQL, and only its owner's attempt can end it")
    void shouldRefuseToRequeueAnAdmittedAttemptWhenAnyCallerTries() {
        UUID admitted = runningJobOwnedBy("owner", Instant.now(), 0);
        admitWithResult(admitted);
        UUID unadmitted = runningJobOwnedBy("owner", Instant.now(), 0);

        int requeued =
                transactionTemplate.execute(s -> jobRepository.requeueOrphan(admitted, "owner", 5, Instant.now()));
        int byStranger = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(admitted, "stranger", 0, Instant.now(), "lost"));
        int byLaterAttempt = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(admitted, "owner", 1, Instant.now(), "lost"));
        int notAdmitted = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(unadmitted, "owner", 0, Instant.now(), "lost"));

        assertThat(requeued).isZero();
        assertThat(byStranger).isZero();
        assertThat(byLaterAttempt).isZero();
        assertThat(notAdmitted).isZero();
        ReviewJob untouched = jobRepository.findById(admitted).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(untouched.getAttempt()).isZero();
        assertThat(jobRepository.findById(unadmitted).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    @DisplayName(
            "a lost admitted review whose original inputs are vouched for continues as one writer of its admission")
    void shouldContinueALostAdmittedReviewUnderItsOriginalAdmissionAsANewAttempt() {
        UUID jobId = runningJobOwnedBy("dead-replica-9", Instant.now().minus(Duration.ofMinutes(5)), 0);
        Result observed = admitWithResult(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 3);
        registerStaleWorker("dead-replica-9");
        ReviewJob before = jobRepository.findById(jobId).orElseThrow();

        sweeper.recoverOrphanedJobs();

        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        // The admission and the evidence it was verified against are the original attempt's, untouched.
        assertThat(continuing.getInput()).isEqualTo(before.getInput());
        assertThat(continuing.getCapture()).isEqualTo(before.getCapture());
        assertThat(ResultAdmissionService.isContinuation(continuing)).isTrue();
        assertThat(ResultAdmissionService.citationAttempt(continuing)).isZero();
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
        // The lost attempt is settled as itself; the new attempt starts from nothing.
        assertThat(continuing.getLlmCalls()).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT total_calls FROM llm_usage_event WHERE source_id::text = ? AND source_attempt = 0",
                        Integer.class,
                        jobId.toString()))
                .containsExactly(3);
        // The lost worker's own attempt is gone: its requeue or its failure matches nothing now.
        Integer staleRequeue = transactionTemplate.execute(s -> {
            continuation.holdRecoveryCustody();
            return continuation.requeue(
                    continuing,
                    "dead-replica-9",
                    new ReviewJobRepository.CompositionRequeue(0, 5, Instant.now(), Instant.now()));
        });
        assertThat(staleRequeue).isZero();
        Integer staleFailure = transactionTemplate.execute(
                s -> jobRepository.failAdmittedOwnedBy(jobId, "dead-replica-9", 0, Instant.now(), "lost"));
        assertThat(staleFailure).isZero();
        fastForwardAvailableAt(jobId);
        assertThat(eligibleForClaim(jobId)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "erased receipt",
                "receipt not indexing its copies",
                "expired inputs",
                "inputs from another admission",
                "suppressed person"
            })
    @DisplayName("a lost admitted review whose original inputs cannot be vouched for fails as before")
    void shouldFailTheLostAdmittedReviewWhenItsOriginalInputsCannotBeContinued(String why) {
        UUID jobId = runningJobOwnedBy("dead-replica-10", Instant.now().minus(Duration.ofMinutes(5)), 0);
        admitWithResult(jobId);
        Instant fresh = Instant.now().plus(Duration.ofHours(1));
        switch (why) {
            case "erased receipt" -> keepOriginalInputs(jobId, 0, "ERASE_REQUESTED", "COMPLETE", fresh);
            case "suppressed person" -> {
                keepOriginalInputs(jobId, 0, "READY", "COMPLETE", fresh);
                jdbc.update(
                        "INSERT INTO identity_restriction(id,provider_id,provider_user_key,team_key,kind) VALUES"
                                + " (?,?,?,'','ERASED')",
                        UUID.randomUUID(),
                        requireNonNull(developer.getProvider()).getId(),
                        "100");
            }
            case "receipt not indexing its copies" -> keepOriginalInputs(jobId, 0, "READY", "UNKNOWN", fresh);
            case "expired inputs" ->
                keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().minusSeconds(1));
            case "inputs from another admission" -> {
                keepOriginalInputs(jobId, 0, "READY", "COMPLETE", fresh);
                jdbc.update(
                        "UPDATE review_composition_basis SET admission_digest = ? WHERE job_id = ?",
                        "another-admission-digest",
                        jobId);
            }
            default -> throw new IllegalArgumentException("Unknown recovery case: " + why);
        }
        spentBeforeItWasLost(jobId, 2);
        registerStaleWorker("dead-replica-10");

        sweeper.recoverOrphanedJobs();

        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo(ResultAdmissionService.INTERRUPTED_AFTER_ADMISSION);
        assertThat(failed.getAttempt()).isZero();
        assertThat(jdbc.queryForList(
                        "SELECT total_calls FROM llm_usage_event WHERE source_id::text = ? AND source_attempt = 0",
                        Integer.class,
                        jobId.toString()))
                .containsExactly(2);
    }

    @Test
    @DisplayName(
            "an admitted attempt that owes private feedback continues once, under its original admission and inputs")
    void shouldContinueOwedPrivateFeedbackOnceUnderTheOriginalAdmission() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        Result observed = admitWithResult(jobId);
        acceptPublicReview(jobId);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiresAt);
        spentBeforeItWasLost(jobId, 4);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();

        assertThat(continueComposition(owing, "live-owner")).isTrue();

        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        assertThat(continuing.getOutput()).isNull();
        // The same admission and the same accepted public review.
        assertThat(ResultAdmissionService.citationAttempt(continuing)).isZero();
        assertThat(continuing.getAdmissionDigest()).isEqualTo("admitted-digest");
        assertThat(continuing.getPublicReviewDigest()).isEqualTo(owing.getPublicReviewDigest());
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
        // The original inputs keep their own expiry: queuing does not extend their retention.
        ReviewCompositionBasis basis = compositionBasisRepository
                .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                .orElseThrow();
        assertThat(basis.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(basis.getAdmissionAttempt()).isZero();
        // The owing attempt is billed once, and the new attempt starts from nothing.
        assertThat(continuing.getLlmCalls()).isZero();
        assertThat(usageCalls(jobId)).containsExactly(4);
        assertThat(continueComposition(owing, "live-owner")).isFalse();
        assertThat(usageCalls(jobId)).containsExactly(4);
    }

    @ParameterizedTest(name = "accepted {0}, all private practices decided: {1}")
    @CsvSource({"PRACTICE_PAGE,false", "PRACTICE_PAGE,true", "CONVERSATION,false", "CONVERSATION,true"})
    @DisplayName(
            "an accepted private decision leaves only the original admission's undecided practices recoverable without a report")
    void shouldContinueOnlyThePrivatePracticesNotYetDecidedWithoutAFeedbackReport(
            FeedbackDestination destination, boolean allDecided) {
        TestUserFactory.ensureAccountForUser(privateAccounts, privateIdentities, developer);
        long providerId = requireNonNull(requireNonNull(developer.getProvider()).getId());
        long accountId = requireNonNull(privateIdentities
                .findActiveByProviderSubject(providerId, "100", null)
                .orElseThrow()
                .getAccount()
                .getId());
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), accountId));
        member.setReviewSelected(true);
        privateMemberships.saveAndFlush(member);

        ItemRef preparedWork = privateRecoveryWork();
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0, preparedWork);
        Practice sibling = withPrivateRecoverySibling(jobId);
        // Pin first: that helper initializes detail; the new results then record admissionAttempt alongside the
        // account.
        pinScriptAssignments(jobId);
        Result decided = admitPrivateNegative(jobId, practice, accountId);
        Result pending = admitPrivateNegative(jobId, sibling, accountId);
        acceptPublicReview(jobId);
        Instant expiry = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiry);
        ReviewJob original = jobRepository.findById(jobId).orElseThrow();
        var rows = List.of(privateProof.snapshot(decided), privateProof.snapshot(pending));
        var pieces = allDecided
                ? List.of(privateQuietDecision(destination, decided), privateQuietDecision(destination, pending))
                : List.of(privateQuietDecision(destination, decided));
        assertThat(reviews.start(workspace.getId(), original.getReviewId())).isTrue();
        var accepted = requireNonNull(transactionTemplate.execute(status -> {
            ReviewJob owned = jobRepository.lockById(jobId).orElseThrow();
            var receipt = privateComposition.prepare(
                    owned,
                    new PrepareFeedbackRequestDTO(
                            owned.getReviewId(),
                            jobId,
                            0,
                            List.of(decided.getId(), pending.getId()),
                            pieces,
                            false,
                            null),
                    rows,
                    new FeedbackCompositionService.Read(
                            new FeedbackCompositionContext(null, null), List.of(), List.of(), null),
                    Map.of(),
                    false);
            jobRepository.saveAndFlush(owned);
            return receipt;
        }));
        assertThat(accepted.feedbackIds()).hasSize(allDecided ? 2 : 1);
        UUID acceptedId = accepted.feedbackIds().getFirst();
        String acceptedBefore = jdbc.queryForObject(
                "SELECT row_to_json(f)::text FROM feedback f WHERE workspace_id=? AND id=?",
                String.class,
                workspace.getId(),
                acceptedId);
        String decisionsBefore = jdbc.queryForObject(
                "SELECT (checks->'feedbackComposition')::text FROM review WHERE workspace_id=? AND id=?",
                String.class,
                workspace.getId(),
                original.getReviewId());
        assertThat(acceptedBefore).isNotBlank();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM feedback WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        acceptedId))
                .isEqualTo("WITHHELD");
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        assertThat(
                        destination == FeedbackDestination.PRACTICE_PAGE
                                ? owing.getPracticePagePreparedAt()
                                : owing.getConversationPreparedAt())
                .isNotNull();

        // Null is the established accepted-public protocol; no returned phase report grants this recovery.
        assertThat(continueComposition(owing, "live-owner")).isEqualTo(!allDecided);

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(allDecided ? ReviewJobStatus.RUNNING : ReviewJobStatus.QUEUED);
        assertThat(after.getAttempt()).isEqualTo(allDecided ? 0 : 1);
        assertThat(ResultAdmissionService.citationAttempt(after)).isZero();
        assertThat(after.getAdmissionDigest()).isEqualTo(original.getAdmissionDigest());
        assertThat(after.getPublicReviewDigest()).isEqualTo(original.getPublicReviewDigest());
        assertThat(after.getInput()).isEqualTo(original.getInput());
        assertThat(after.getCapture()).isEqualTo(original.getCapture());
        assertThat(practiceResultRepository.findAllById(List.of(decided.getId(), pending.getId())))
                .extracting(Result::getOutcome)
                .containsExactly(ResultOutcome.NOT_MET, ResultOutcome.NOT_MET);
        assertThat(jdbc.queryForObject(
                        "SELECT row_to_json(f)::text FROM feedback f WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        acceptedId))
                .isEqualTo(acceptedBefore);
        assertThat(jdbc.queryForObject(
                        "SELECT (checks->'feedbackComposition')::text FROM review WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        original.getReviewId()))
                .isEqualTo(decisionsBefore);
        assertThat(compositionBasisRepository
                        .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                        .orElseThrow()
                        .getExpiresAt())
                .isEqualTo(expiry);
        // Repeating the old owner's request can neither queue twice nor replace the accepted decision.
        assertThat(continueComposition(owing, "live-owner")).isFalse();
    }

    private static ComposedFeedback privateQuietDecision(FeedbackDestination destination, Result result) {
        return new ComposedFeedback(
                destination,
                List.of(result.getId()),
                null,
                List.of(),
                FeedbackStopReason.COMPOSER_WITHHELD,
                new FeedbackCompositionTrace(
                        List.of(),
                        List.of(new FeedbackCompositionTrace.QuietDecision(
                                List.of(new FeedbackCompositionTrace.Support(result.getId(), null)),
                                "BELOW_BAR",
                                List.of())),
                        null,
                        null),
                null);
    }

    private ItemRef privateRecoveryWork() {
        var provider = requireNonNull(developer.getProvider());
        var now = Instant.now();
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "17",
                new ConnectionConfig.GitHubAppConfig(17L, "private-recovery", null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        privateConnections.saveAndFlush(connection);
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("private-recovery");
        repository.setNameWithOwner("private-recovery/work");
        repository.setHtmlUrl("https://github.com/private-recovery/work");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(now);
        repository.setCreatedAt(now);
        repository.setUpdatedAt(now);
        repository = privateRepositories.saveAndFlush(repository);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        privateMonitors.saveAndFlush(monitor);
        var pull = new PullRequest();
        pull.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        pull.setProvider(provider);
        pull.setRepository(repository);
        pull.setNumber(17);
        pull.setTitle("Preserve failure context");
        pull.setBody("verified quote");
        pull.setState(Issue.State.OPEN);
        pull.setAuthor(developer);
        pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/17");
        pull.setCreatedAt(now);
        pull.setUpdatedAt(now);
        pull.setHeadRefOid("a".repeat(40));
        pull.setBaseRefOid("b".repeat(40));
        pull.setHeadRefName("failure-context");
        pull.setBaseRefName("main");
        pull = privatePullRequests.saveAndFlush(pull);
        var parent = new Item();
        parent.setWorkspaceId(workspace.getId());
        parent.setKind(ScmItemEvents.REPOSITORY);
        parent.setSourceId(repository.getId());
        parent.setCreatedAt(now);
        parent = privateItems.saveAndFlush(parent);
        var item = new Item();
        item.setWorkspaceId(workspace.getId());
        item.setKind(ScmItemEvents.PULL_REQUEST);
        item.setParentId(parent.getId());
        item.setSourceId(pull.getId());
        item.setCreatedAt(now);
        item = privateItems.saveAndFlush(item);
        var ref = new ItemRef(
                workspace.getId(), item.getId(), item.getKind(), item.getSourceId(), item.getParentId(), null);
        assertThat(privateRepositories.isMonitored(workspace.getId(), repository.getId()))
                .isTrue();
        assertThat(privateSourceAccess.permits(ref, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        return ref;
    }

    private Practice withPrivateRecoverySibling(UUID jobId) {
        Practice sibling = runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace,
                "private-recovery-sibling",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(
                        new ItemKind("scm.pull_request"), "Private recovery sibling", "The sibling definition", null),
                null,
                0,
                null));
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            long itemId = job.getInput().itemId();
            jdbc.update(
                    "INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)"
                            + " VALUES (?,?,?,?,'READY')",
                    job.getReviewId(),
                    workspace.getId(),
                    sibling.getId(),
                    sibling.getCurrentVersion().getId());
            jdbc.update(
                    "INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)"
                            + " SELECT ?,?,?,?,item_version FROM review WHERE workspace_id=? AND id=?",
                    job.getReviewId(),
                    workspace.getId(),
                    sibling.getId(),
                    itemId,
                    workspace.getId(),
                    job.getReviewId());
            job.setInput(new ReviewJobInput(
                    itemId,
                    List.of(
                            new ReviewAssignmentId(job.getReviewId(), practice.getId(), itemId),
                            new ReviewAssignmentId(job.getReviewId(), sibling.getId(), itemId))));
            jobRepository.saveAndFlush(job);
        });
        return sibling;
    }

    private Result admitPrivateNegative(UUID jobId, Practice about, long accountId) {
        return requireNonNull(transactionTemplate.execute(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            var evidence = AdmittedResultFixtures.evidence("scm.pull-request.core");
            String version = requireNonNull(jdbc.queryForObject(
                    "SELECT item_version FROM review WHERE workspace_id=? AND id=?",
                    String.class,
                    workspace.getId(),
                    job.getReviewId()));
            var row = practiceResultRepository.saveAndFlush(Result.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(job.getWorkspaceId())
                    .reviewId(job.getReviewId())
                    .practiceId(about.getId())
                    .practiceVersionId(about.getCurrentVersion().getId())
                    .itemId(job.getInput().itemId())
                    .itemVersion(version)
                    .developerId(accountId)
                    .outcome(ResultOutcome.NOT_MET)
                    .severity(Severity.MAJOR)
                    .summary("Keep failure context")
                    .explanation("The captured event loses its failure context.")
                    .evidence(evidence)
                    .evidenceVersion((short) 1)
                    .coverage(objectMapper.readTree(
                            "{\"source\":\"claimed\",\"complete\":true,\"selected\":1,\"checked\":1,\"total\":1}"))
                    .recordedAt(Instant.now())
                    .build());
            privateProblems.saveAndFlush(Problem.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(job.getWorkspaceId())
                    .resultId(row.getId())
                    .position(0)
                    .severity(Severity.MAJOR)
                    .summary("Keep failure context")
                    .explanation("The captured event loses its failure context.")
                    .citations(evidence.path("citations"))
                    .basis(objectMapper
                            .createObjectNode()
                            .put("type", "introduced")
                            .put("head", 0)
                            .put("explanation", "The captured change introduces the gap."))
                    .build());
            jdbc.update(
                    "UPDATE review_item SET state='JUDGED',result_id=?,job_id=?,"
                            + " detail=jsonb_build_object('developerId',?,'admissionAttempt',0)"
                            + " WHERE workspace_id=? AND review_id=? AND practice_id=? AND item_id=?",
                    row.getId(),
                    job.getId(),
                    accountId,
                    job.getWorkspaceId(),
                    job.getReviewId(),
                    about.getId(),
                    job.getInput().itemId());
            job.setAdmissionDigest("admitted-digest");
            job.setAdmissionAttempt(0);
            job.setCapture(objectMapper
                    .createObjectNode()
                    .set("manifest", objectMapper.createObjectNode().put("contractVersion", "2.0.0")));
            jobRepository.saveAndFlush(job);
            return row;
        }));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"retry limit reached", "expired inputs", "erased receipt", "another worker"})
    @DisplayName("an admitted attempt that owes private feedback ends as itself when a fence does not hold")
    void shouldNotContinueOwedPrivateFeedbackWhenAFenceDoesNotHold(String why) {
        int attempt = why.equals("retry limit reached") ? 5 : 0;
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), attempt);
        admitWithResult(jobId);
        acceptPublicReview(jobId);
        Instant fresh = Instant.now().plus(Duration.ofHours(1));
        if (why.equals("expired inputs")) {
            keepOriginalInputs(
                    jobId, attempt, "READY", "COMPLETE", Instant.now().minusSeconds(1));
        } else if (why.equals("erased receipt")) {
            keepOriginalInputs(jobId, attempt, "ERASE_REQUESTED", "COMPLETE", fresh);
        } else {
            keepOriginalInputs(jobId, attempt, "READY", "COMPLETE", fresh);
        }
        spentBeforeItWasLost(jobId, 2);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();

        assertThat(continueComposition(owing, why.equals("another worker") ? "stranger" : "live-owner"))
                .isFalse();

        ReviewJob kept = jobRepository.findById(jobId).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(kept.getWorkerId()).isEqualTo("live-owner");
        assertThat(kept.getAttempt()).isEqualTo(attempt);
        assertThat(usageCalls(jobId)).isEmpty();
    }

    @ParameterizedTest(name = "{0}, private piece prepared: {1}")
    @CsvSource({"SAFETY,false", "STALL,false", "SAFETY,true", "STALL,true"})
    @DisplayName(
            "the admitting attempt hands a stopped, unaccepted review on the work to one more attempt, and only once")
    void shouldContinueAnUnacceptedReviewOnTheWorkOnceFromTheAdmittingAttempt(String stop, boolean privatePiece) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        Result observed = admitWithResult(jobId);
        if (privatePiece) jdbc.update("UPDATE review_job SET conversation_prepared_at = now() WHERE id = ?", jobId);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiresAt);
        spentBeforeItWasLost(jobId, 3);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        Instant preparedAt = owing.getConversationPreparedAt();

        assertThat(continueComposition(owing, "live-owner", publicStop(stop))).isTrue();

        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        assertThat(continuing.getOutput()).isNull();
        // The same admission and results; still no accepted review on the work.
        assertThat(ResultAdmissionService.citationAttempt(continuing)).isZero();
        assertThat(continuing.getAdmissionDigest()).isEqualTo("admitted-digest");
        assertThat(continuing.getPublicReviewDigest()).isNull();
        // A prepared private piece stays exactly as it was.
        assertThat(continuing.getConversationPreparedAt()).isEqualTo(preparedAt);
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
        assertThat(compositionBasisRepository
                        .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                        .orElseThrow()
                        .getExpiresAt())
                .isEqualTo(expiresAt);
        assertThat(usageCalls(jobId)).containsExactly(3);

        // The attempt that continues it cannot hand the review on the work on again.
        jdbc.update(
                "UPDATE review_job SET status = 'RUNNING', worker_id = 'next-worker', started_at = now(),"
                        + " execution_started_at = now() WHERE id = ?",
                jobId);
        ReviewJob claimed = jobRepository.findById(jobId).orElseThrow();
        assertThat(continueComposition(claimed, "next-worker", publicStop(stop)))
                .isFalse();
        ReviewJob ended = jobRepository.findById(jobId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(ended.getAttempt()).isEqualTo(1);
        assertThat(usageCalls(jobId)).containsExactly(3);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SAFETY", "STALL"})
    @DisplayName("an admission on a later attempt is still the admitting attempt, and hands the review on once")
    void shouldContinueAnUnacceptedReviewOnTheWorkFromAnAdmissionOnALaterAttempt(String stop) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 2);
        admitWithResult(jobId);
        keepOriginalInputs(jobId, 2, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 1);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();

        assertThat(continueComposition(owing, "live-owner", publicStop(stop))).isTrue();

        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(3);
        assertThat(ResultAdmissionService.citationAttempt(continuing)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                        "SELECT total_calls FROM llm_usage_event WHERE source_id::text = ? AND source_attempt = 2",
                        Integer.class,
                        jobId.toString()))
                .containsExactly(1);

        jdbc.update(
                "UPDATE review_job SET status = 'RUNNING', worker_id = 'next-worker', started_at = now(),"
                        + " execution_started_at = now() WHERE id = ?",
                jobId);
        assertThat(continueComposition(jobRepository.findById(jobId).orElseThrow(), "next-worker", publicStop(stop)))
                .isFalse();
        assertThat(jobRepository.findById(jobId).orElseThrow().getAttempt()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "continuing attempt",
                "no retries",
                "retry limit reached",
                "expired inputs",
                "erased receipt",
                "receipt not indexing its copies",
                "another worker"
            })
    @DisplayName("an unaccepted review on the work stays with its attempt when a fence does not hold")
    void shouldNotContinueAnUnacceptedReviewOnTheWorkWhenAFenceDoesNotHold(String why) {
        int attempt = switch (why) {
            case "continuing attempt" -> 1;
            case "retry limit reached" -> 5;
            default -> 0;
        };
        int admittedAt = why.equals("continuing attempt") ? 0 : attempt;
        if (why.equals("no retries")) when(properties.maxRetries()).thenReturn(0);
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), attempt);
        admitWithResult(jobId);
        if (admittedAt != attempt)
            jdbc.update("UPDATE review_job SET admission_attempt = ? WHERE id = ?", admittedAt, jobId);
        Instant fresh = Instant.now().plus(Duration.ofHours(1));
        switch (why) {
            case "expired inputs" ->
                keepOriginalInputs(
                        jobId, admittedAt, "READY", "COMPLETE", Instant.now().minusSeconds(1));
            case "erased receipt" -> keepOriginalInputs(jobId, admittedAt, "ERASE_REQUESTED", "COMPLETE", fresh);
            case "receipt not indexing its copies" -> keepOriginalInputs(jobId, admittedAt, "READY", "UNKNOWN", fresh);
            default -> keepOriginalInputs(jobId, admittedAt, "READY", "COMPLETE", fresh);
        }
        spentBeforeItWasLost(jobId, 2);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();

        // The report says the run stopped the review on the work; the fence alone decides.
        assertThat(continueComposition(
                        owing, why.equals("another worker") ? "stranger" : "live-owner", publicStop("SAFETY")))
                .isFalse();

        ReviewJob kept = jobRepository.findById(jobId).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(kept.getWorkerId()).isEqualTo("live-owner");
        assertThat(kept.getAttempt()).isEqualTo(attempt);
        assertThat(usageCalls(jobId)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_job_script_run WHERE job_id = ?", Integer.class, jobId))
                .isZero();
    }

    @ParameterizedTest(name = "public review accepted: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName(
            "a private stop that never heard whether its public review was accepted continues only on the row's review")
    void shouldDecideAPrivateStopWithAnUnknownPublicReviewOnTheReviewTheRowAccepted(boolean accepted) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        pinScriptAssignments(jobId);
        if (accepted) acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 2);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        // Both answers to the public preparation were lost: the report names only the private stop.
        byte[] privateStop = feedbackReport("admitted-digest", failure("PRIVATE_FEEDBACK", "SAFETY"));

        assertThat(continueComposition(owing, "live-owner", null, scriptReport(practice.getSlug()), privateStop))
                .isEqualTo(accepted);

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        Integer scriptRuns = jdbc.queryForObject(
                "SELECT count(*) FROM review_job_script_run WHERE job_id = ?", Integer.class, jobId);
        if (accepted) {
            // The review the row accepted owes private feedback, as it did before.
            assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(after.getAttempt()).isEqualTo(1);
            assertThat(after.getPublicReviewDigest()).isEqualTo("b".repeat(64));
            assertThat(usageCalls(jobId)).containsExactly(2);
            assertThat(scriptRuns).isEqualTo(1);
        } else {
            // Nothing the report says opens the review on the work again: the attempt ends as itself.
            assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
            assertThat(after.getWorkerId()).isEqualTo("live-owner");
            assertThat(after.getAttempt()).isZero();
            assertThat(after.getOutput()).isNull();
            assertThat(after.getPublicReviewDigest()).isNull();
            assertThat(usageCalls(jobId)).isEmpty();
            assertThat(scriptRuns).isZero();
        }
    }

    @ParameterizedTest(name = "{0}, public review accepted: {1}")
    @CsvSource({
        "no report,false",
        "no report,true",
        "malformed report,false",
        "malformed report,true",
        "trailing tokens,false",
        "trailing tokens,true",
        "not an object,false",
        "not an object,true",
        "another admission,false",
        "another admission,true",
        "no failures,false",
        "no failures,true",
        "public budget,false",
        "public budget,true",
        "unknown public reason,false",
        "unknown public reason,true",
        "conflicting public reasons,false",
        "conflicting public reasons,true"
    })
    @DisplayName("a report that does not say the run stopped the review on the work only refuses the unaccepted one")
    void shouldContinueOnlyTheAcceptedReviewWhenTheReportDoesNotSayTheRunStoppedTheReviewOnTheWork(
            String why, boolean accepted) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        if (accepted) acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 2);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        String stopped = failure("PUBLIC_REVIEW", "SAFETY");
        byte @Nullable [] report = switch (why) {
            case "no report" -> null;
            case "malformed report" -> "{\"admissionDigest\":".getBytes(StandardCharsets.UTF_8);
            case "trailing tokens" ->
                (new String(publicStop("SAFETY"), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8);
            case "not an object" -> ("[" + stopped + "]").getBytes(StandardCharsets.UTF_8);
            case "another admission" -> feedbackReport("another-admission-digest", stopped);
            case "no failures" -> "{\"admissionDigest\":\"admitted-digest\"}".getBytes(StandardCharsets.UTF_8);
            case "public budget" -> publicStop("BUDGET");
            case "unknown public reason" -> publicStop("TIME");
            case "conflicting public reasons" ->
                feedbackReport("admitted-digest", stopped, failure("PUBLIC_REVIEW", "BUDGET"));
            default -> throw new IllegalArgumentException("Unknown report case: " + why);
        };

        assertThat(continueComposition(owing, "live-owner", null, null, report)).isEqualTo(accepted);

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(accepted ? ReviewJobStatus.QUEUED : ReviewJobStatus.RUNNING);
        assertThat(after.getAttempt()).isEqualTo(accepted ? 1 : 0);
        if (accepted) assertThat(usageCalls(jobId)).containsExactly(2);
        else assertThat(usageCalls(jobId)).isEmpty();
    }

    @Test
    @DisplayName("an owed attempt that returned its runner report is billed from that report once")
    void shouldBillTheReturnedRunnerReportOnceWhenAnOwedAttemptContinues() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        // The proxy saw fewer calls than the runner reported, as when a call bypassed its counters.
        jdbc.update(
                "UPDATE review_job SET execution_started_at = now(), llm_calls = 4, llm_input_tokens = 900,"
                        + " llm_output_tokens = 600 WHERE id = ?",
                jobId);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        var report = new AgentResult.LlmUsage("m", 1000, 700, 0, 0, 0, 0.0, 6);

        assertThat(continueComposition(owing, "live-owner", report)).isTrue();
        assertThat(continueComposition(owing, "live-owner", report)).isFalse();

        assertThat(jdbc.queryForList(
                        "SELECT input_tokens || '/' || output_tokens || '/' || total_calls FROM llm_usage_event"
                                + " WHERE source_id::text = ? AND source_attempt = 0",
                        String.class,
                        jobId.toString()))
                .containsExactly("1000/700/6");
        assertThat(jobRepository.findById(jobId).orElseThrow().getLlmCalls()).isZero();
    }

    @Test
    @DisplayName("an owed attempt keeps its returned practice script runs once, for its own admitted practices")
    void shouldKeepTheReturnedPracticeScriptRunsOfAGrantedOwedAttemptOnce() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        Practice sibling = withCaptureRefusedSibling(jobId);
        pinScriptAssignments(jobId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 1);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        byte[] report = scriptReport(practice.getSlug(), sibling.getSlug());

        assertThat(continueComposition(owing, "live-owner", null, report)).isTrue();
        assertThat(continueComposition(owing, "live-owner", null, report)).isFalse();

        assertThat(jobRepository.findById(jobId).orElseThrow().getAttempt()).isEqualTo(1);
        // Attempt 0's row only: its pinned version and the counts it reported, not the sibling its capture refused.
        assertThat(jdbc.queryForList(
                        "SELECT attempt || ':' || practice_id || ':' || practice_version_id || ':' || status || ':'"
                                + " || (calls ->> 'review') FROM review_job_script_run WHERE job_id = ?",
                        String.class,
                        jobId))
                .containsExactly("0:" + practice.getId() + ":"
                        + practice.getCurrentVersion().getId() + ":OK:1");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"no report", "malformed report", "refused requeue"})
    @DisplayName(
            "an owed attempt records no practice script runs at its requeue without a granted requeue and a report")
    void shouldRecordNoPracticeScriptRunsAtTheRequeueWithoutAGrantedRequeueAndAReport(String why) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        pinScriptAssignments(jobId);
        acceptPublicReview(jobId);
        // Expired original inputs refuse the requeue the attempt asked for.
        Instant expiresAt = why.equals("refused requeue")
                ? Instant.now().minusSeconds(1)
                : Instant.now().plus(Duration.ofHours(1));
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiresAt);
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();
        byte[] report = why.equals("no report")
                ? null
                : why.equals("malformed report")
                        ? "{".getBytes(StandardCharsets.UTF_8)
                        : scriptReport(practice.getSlug());

        assertThat(continueComposition(owing, "live-owner", null, report)).isEqualTo(!why.equals("refused requeue"));

        // A refused requeue leaves the runs to the attempt's ordinary terminal write.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_job_script_run WHERE job_id = ?", Integer.class, jobId))
                .isZero();
    }

    @ParameterizedTest(name = "private piece prepared: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a lost admitted review with an accepted public review continues whatever private piece it prepared")
    void shouldContinueALostAdmittedReviewWithAnAcceptedPublicReview(boolean privatePiece) {
        UUID jobId = runningJobOwnedBy("dead-replica-11", Instant.now().minus(Duration.ofMinutes(5)), 0);
        admitWithResult(jobId);
        acceptPublicReview(jobId);
        if (privatePiece) jdbc.update("UPDATE review_job SET practice_page_prepared_at = now() WHERE id = ?", jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        registerStaleWorker("dead-replica-11");

        sweeper.recoverOrphanedJobs();

        // The new attempt's admission answer says which practices the prepared pieces decide; it composes the rest.
        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        assertThat(continuing.getPublicReviewDigest()).isEqualTo("b".repeat(64));
        assertThat(continuing.getPracticePagePreparedAt() != null).isEqualTo(privatePiece);
    }

    @Test
    @DisplayName(
            "an admitted run the sandbox timed out continues its owed private feedback as the same job, ending nothing")
    void shouldContinueATimedOutAdmittedRunThatOwesPrivateFeedbackAsANewAttemptOfTheSameJob() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        Result observed = admitWithResult(jobId);
        acceptPublicReview(jobId);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiresAt);
        spentBeforeItWasLost(jobId, 2);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();

        assertThat(executorOwnedBy("live-owner").executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isNull();

        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        assertThat(continuing.getOutput()).isNull();
        assertThat(continuing.getExitCode()).isNull();
        assertThat(continuing.getErrorMessage()).isNull();
        // The original admission, its results, the accepted public review and the retained inputs are unchanged.
        assertThat(ResultAdmissionService.citationAttempt(continuing)).isZero();
        assertThat(continuing.getAdmissionDigest()).isEqualTo("admitted-digest");
        assertThat(continuing.getPublicReviewDigest()).isEqualTo("b".repeat(64));
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
        ReviewCompositionBasis basis = compositionBasisRepository
                .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                .orElseThrow();
        assertThat(basis.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(basis.getAdmissionAttempt()).isZero();
        // The stopped attempt is billed once, from the proxy's counts: the sandbox left no runner report.
        assertThat(usageCalls(jobId)).containsExactly(2);
        assertThat(continuing.getLlmCalls()).isZero();
    }

    @ParameterizedTest(name = "all private practices decided: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a timed-out run continues only while a prepared private decision leaves a practice undecided")
    void shouldContinueATimedOutRunOnlyForPrivatePracticesNotYetDecided(boolean allDecided) {
        TestUserFactory.ensureAccountForUser(privateAccounts, privateIdentities, developer);
        long providerId = requireNonNull(requireNonNull(developer.getProvider()).getId());
        long accountId = requireNonNull(privateIdentities
                .findActiveByProviderSubject(providerId, "100", null)
                .orElseThrow()
                .getAccount()
                .getId());
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), accountId));
        member.setReviewSelected(true);
        privateMemberships.saveAndFlush(member);
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0, privateRecoveryWork());
        Practice sibling = withPrivateRecoverySibling(jobId);
        pinScriptAssignments(jobId);
        Result decided = admitPrivateNegative(jobId, practice, accountId);
        Result pending = admitPrivateNegative(jobId, sibling, accountId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        ReviewJob original = jobRepository.findById(jobId).orElseThrow();
        var pieces = allDecided
                ? List.of(
                        privateQuietDecision(FeedbackDestination.PRACTICE_PAGE, decided),
                        privateQuietDecision(FeedbackDestination.PRACTICE_PAGE, pending))
                : List.of(privateQuietDecision(FeedbackDestination.PRACTICE_PAGE, decided));
        assertThat(reviews.start(workspace.getId(), original.getReviewId())).isTrue();
        var accepted = requireNonNull(transactionTemplate.execute(status -> {
            ReviewJob owned = jobRepository.lockById(jobId).orElseThrow();
            var receipt = privateComposition.prepare(
                    owned,
                    new PrepareFeedbackRequestDTO(
                            owned.getReviewId(),
                            jobId,
                            0,
                            List.of(decided.getId(), pending.getId()),
                            pieces,
                            false,
                            null),
                    List.of(privateProof.snapshot(decided), privateProof.snapshot(pending)),
                    new FeedbackCompositionService.Read(
                            new FeedbackCompositionContext(null, null), List.of(), List.of(), null),
                    Map.of(),
                    false);
            jobRepository.saveAndFlush(owned);
            return receipt;
        }));
        String acceptedBefore = jdbc.queryForObject(
                "SELECT row_to_json(f)::text FROM feedback f WHERE workspace_id=? AND id=?",
                String.class,
                workspace.getId(),
                accepted.feedbackIds().getFirst());
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();

        assertThat(executorOwnedBy("live-owner").executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isEqualTo(allDecided ? ReviewJobStatus.TIMED_OUT : null);

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(allDecided ? ReviewJobStatus.TIMED_OUT : ReviewJobStatus.QUEUED);
        assertThat(after.getAttempt()).isEqualTo(allDecided ? 0 : 1);
        assertThat(after.getAdmissionDigest()).isEqualTo(original.getAdmissionDigest());
        assertThat(after.getPublicReviewDigest()).isEqualTo(original.getPublicReviewDigest());
        // A continued job keeps its retained inputs; an ended one drops them.
        assertThat(compositionBasisRepository
                        .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                        .isPresent())
                .isEqualTo(!allDecided);
        // The accepted decision stays exactly as it was either way.
        assertThat(jdbc.queryForObject(
                        "SELECT row_to_json(f)::text FROM feedback f WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        accepted.feedbackIds().getFirst()))
                .isEqualTo(acceptedBefore);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not admitted", "public review not accepted", "expired inputs", "retry limit reached"})
    @DisplayName("a timed-out run whose continuation a fence refuses ends timed out and drops its retained inputs")
    void shouldEndATimedOutRunAsTimedOutWhenAFenceRefusesItsContinuation(String why) {
        int attempt = why.equals("retry limit reached") ? 5 : 0;
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), attempt);
        if (!why.equals("not admitted")) admitWithResult(jobId);
        if (!why.equals("not admitted") && !why.equals("public review not accepted")) acceptPublicReview(jobId);
        keepOriginalInputs(
                jobId,
                attempt,
                "READY",
                "COMPLETE",
                why.equals("expired inputs")
                        ? Instant.now().minusSeconds(1)
                        : Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 2);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();

        assertThat(executorOwnedBy("live-owner").executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.TIMED_OUT);

        ReviewJob ended = jobRepository.findById(jobId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.TIMED_OUT);
        assertThat(ended.getAttempt()).isEqualTo(attempt);
        assertThat(ended.getErrorMessage()).isEqualTo("The container timed out.");
        assertThat(compositionBasisRepository.findByWorkspaceIdAndJobId(workspace.getId(), jobId))
                .isEmpty();
        assertThat(usageCalls(jobId)).containsExactly(2);
    }

    @Test
    @DisplayName("a timed-out run reported by a worker that no longer owns it neither continues nor ends the job")
    void shouldLeaveATimedOutRunAloneWhenAnotherWorkerReportsIt() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        spentBeforeItWasLost(jobId, 2);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();

        assertThat(executorOwnedBy("stranger").executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isNull();

        ReviewJob kept = jobRepository.findById(jobId).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(kept.getWorkerId()).isEqualTo("live-owner");
        assertThat(kept.getAttempt()).isZero();
        assertThat(compositionBasisRepository.findByWorkspaceIdAndJobId(workspace.getId(), jobId))
                .isPresent();
        assertThat(usageCalls(jobId)).isEmpty();
    }

    /*
     * The real executor over this database, with the real recovery, continuation, usage and script-run owners. Only
     * the sandbox, which reports a hard timeout without any runner output, and collaborators executePrepared does not
     * reach are stand-ins.
     */
    private ReviewJobExecutor executorOwnedBy(String workerId) {
        var timedOut = new SandboxResult(124, Map.of(), "stopped", true, Duration.ofMinutes(15));
        var sandbox = mock(SandboxManager.class);
        when(sandbox.execute(any())).thenReturn(timedOut);
        var agent = mock(PracticePiAdapter.class);
        when(agent.parseResult(timedOut)).thenReturn(new AgentResult(false, Map.of()));
        var worker = mock(WorkerProperties.class);
        when(worker.resolvedWorkerId()).thenReturn(workerId);
        var executor = new ReviewJobExecutor(
                properties,
                jobRepository,
                mock(DefaultReviewExecutionHandler.class),
                agent,
                mock(WorkerJwtIssuer.class),
                sandbox,
                SandboxTestLimits.PROPERTIES,
                mock(AsyncTaskExecutor.class),
                transactionTemplate,
                objectMapper,
                meterRegistry,
                new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                attemptUsage,
                new PracticeScriptRunRecorder(scriptReports, scriptRuns, meterRegistry, objectMapper, assignments),
                null,
                Optional.empty(),
                worker,
                mock(ReviewJobClaimService.class),
                recovery);
        executor.setCompositionContinuation(continuation);
        return executor;
    }

    @ParameterizedTest(name = "retained task envelope: {0}")
    @ValueSource(strings = {"none", "the admitting attempt's", "malformed"})
    @DisplayName("a continued attempt is staged with its own task envelope beside its admission's unchanged inputs")
    void shouldStageTheContinuedAttemptWithItsOwnTaskEnvelope(String retainedEnvelope) throws Exception {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        pinScriptAssignments(jobId);
        acceptPublicReview(jobId);
        ReviewJob admitting = jobRepository.findById(jobId).orElseThrow();
        Map<String, byte[]> captured = admittedInputs();
        var retained = new TreeMap<>(captured);
        // The admission never retains a task envelope; one in the retained set must not stand in for this attempt's.
        switch (retainedEnvelope) {
            case "the admitting attempt's" ->
                retained.put(
                        SandboxLayout.TASK_ENVELOPE_FILENAME,
                        objectMapper.writeValueAsBytes(
                                TaskEnvelope.practiceReview(admitting.getReviewId(), jobId, 0, workspace.getId())));
            case "malformed" ->
                retained.put(SandboxLayout.TASK_ENVELOPE_FILENAME, "{".getBytes(StandardCharsets.UTF_8));
            default -> {}
        }
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", expiresAt, retained);
        assertThat(continueComposition(admitting, "live-owner")).isTrue();
        // The next worker holds the queued attempt and stages it as the executor does.
        jdbc.update(
                "UPDATE review_job SET status = 'RUNNING', worker_id = 'next-worker', started_at = now() WHERE id = ?",
                jobId);
        ReviewJob claimed = jobRepository.findById(jobId).orElseThrow();

        try (var prepared = continuation.prepareInputs(claimed)) {
            var staged = prepared.filesOnDisk();
            // The runner reads this file before anything else, and stops before any model call without it.
            assertThat(staged).containsKey(SandboxLayout.TASK_ENVELOPE_FILENAME);
            TaskEnvelope task = objectMapper.readValue(
                    Files.readAllBytes(requireNonNull(staged.get(SandboxLayout.TASK_ENVELOPE_FILENAME))),
                    TaskEnvelope.class);
            assertThat(task.attempt()).isEqualTo(1);
            assertThat(task.jobId()).isEqualTo(jobId);
            assertThat(task.reviewId()).isEqualTo(claimed.getReviewId());
            assertThat(task.workspaceId()).isEqualTo(Long.toString(workspace.getId()));
            assertThat(task.prompt()).isNotBlank();
            // The inputs the task names are the admission's, byte for byte.
            assertThat(staged).containsKeys(task.manifest(), task.compositionRequest(), task.practiceIndex());
            for (var file : captured.entrySet()) {
                assertThat(Files.readAllBytes(requireNonNull(staged.get(file.getKey()))))
                        .as(file.getKey())
                        .isEqualTo(file.getValue());
            }
            assertThat(staged).containsKey(ReviewCompositionContinuation.CONTINUATION_PATH);
        }

        // Staging the attempt left the admission and what it retained as they were.
        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(ResultAdmissionService.citationAttempt(after)).isZero();
        assertThat(after.getAdmissionDigest()).isEqualTo("admitted-digest");
        ReviewCompositionBasis basis = compositionBasisRepository
                .findByWorkspaceIdAndJobId(workspace.getId(), jobId)
                .orElseThrow();
        assertThat(basis.getAdmissionAttempt()).isZero();
        assertThat(basis.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(basis.getBasisDigest()).isEqualTo(ReviewCompositionBasis.digest(retained));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"expired inputs", "erased receipt"})
    @DisplayName("an owed continuation refused at staging runs nothing, and its admitted review ends partial")
    void shouldSettleAnOwedContinuationRefusedAtStagingAsAPartialAdmittedReview(String why) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        Result observed = admitWithResult(jobId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        assertThat(continueComposition(jobRepository.findById(jobId).orElseThrow(), "live-owner"))
                .isTrue();
        if (why.equals("expired inputs"))
            jdbc.update(
                    "UPDATE review_composition_basis SET expires_at = now() - interval '1 second' WHERE job_id = ?",
                    jobId);
        else jdbc.update("UPDATE person_evidence_copy SET state = 'ERASE_REQUESTED' WHERE job_id = ?", jobId);
        // The next worker holds the queued attempt; staging refuses before a folder or a sandbox exists.
        jdbc.update(
                "UPDATE review_job SET status = 'RUNNING', worker_id = 'next-worker', started_at = now() WHERE id = ?",
                jobId);
        ReviewJob claimed = jobRepository.findById(jobId).orElseThrow();
        assertThatThrownBy(() -> continuation.prepareInputs(claimed)).isInstanceOf(JobPreparationException.class);
        // The executor's failure write for a refused preparation.
        Integer failed = transactionTemplate.execute(status -> jobRepository.transitionStatusOwnedBy(
                jobId,
                ReviewJobStatus.FAILED,
                Instant.now(),
                "The original capture no longer permits composition",
                "next-worker",
                claimed.getAttempt()));
        assertThat(failed).isEqualTo(1);

        completion.tick();

        assertThat(jdbc.queryForObject("SELECT status FROM review WHERE id = ?", String.class, claimed.getReviewId()))
                .isEqualTo("PARTIAL");
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM review_item WHERE review_id = ?", String.class, claimed.getReviewId()))
                .isEqualTo("JUDGED");
    }

    @ParameterizedTest(name = "public review committed: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("an owed continuation waiting on a preparation's row lock decides on what it committed")
    void shouldDecideOnTheCommittedPreparationWhenTheOwedContinuationWaits(boolean committed) throws Exception {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));
        ReviewJob owing = jobRepository.findById(jobId).orElseThrow();

        // Without a report, only a committed public review leaves private feedback owed.
        whileLocked(
                jobId,
                committed,
                job -> job.setPublicReviewDigest("b".repeat(64)),
                () -> continueComposition(owing, "live-owner"));

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(committed ? ReviewJobStatus.QUEUED : ReviewJobStatus.RUNNING);
        assertThat(after.getAttempt()).isEqualTo(committed ? 1 : 0);
    }

    @ParameterizedTest(name = "admission committed: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("recovery waiting on the admission's row lock decides on what the admission left behind")
    void shouldDecideOnTheCommittedAdmissionWhenRecoveryWaitsForItsRowLock(boolean committed) throws Exception {
        UUID jobId = runningJobOwnedBy("dead-replica-7", Instant.now().minus(Duration.ofMinutes(5)), 0);
        registerStaleWorker("dead-replica-7");

        whileRecoveryWaits(jobId, committed, job -> {
            job.setAdmissionDigest("admitted-digest");
            job.setAdmissionAttempt(job.getAttempt());
        });

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        if (committed) {
            assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
            assertThat(after.getAttempt()).isZero();
            assertThat(ResultAdmissionService.isAdmitted(after)).isTrue();
        } else {
            assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(after.getAttempt()).isEqualTo(1);
            assertThat(ResultAdmissionService.isAdmitted(after)).isFalse();
        }
    }

    @Test
    @DisplayName("recovery leaves alone an attempt a new claim took while it waited for the row lock")
    void shouldLeaveTheNewClaimAloneWhenTheOrphanWasReclaimedWhileRecoveryWaited() throws Exception {
        UUID jobId = runningJobOwnedBy("dead-replica-8", Instant.now().minus(Duration.ofMinutes(5)), 5);
        registerStaleWorker("dead-replica-8");

        whileRecoveryWaits(jobId, true, job -> {
            job.setWorkerId("live-sibling");
            job.setAttempt(6);
        });

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(after.getWorkerId()).isEqualTo("live-sibling");
        assertThat(after.getAttempt()).isEqualTo(6);
        assertThat(after.getErrorMessage()).isNull();
    }

    private void whileRecoveryWaits(UUID jobId, boolean commit, Consumer<ReviewJob> change) throws Exception {
        whileLocked(jobId, commit, change, sweeper::recoverOrphanedJobs);
    }

    /*
     * Holds the job's row lock as the admission or a preparation does, changes it, and lets the contender run
     * against it: the contender blocks on the lock, then decides on what this transaction committed or rolled back.
     */
    private void whileLocked(UUID jobId, boolean commit, Consumer<ReviewJob> change, Runnable contender)
            throws Exception {
        var locked = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> transactionTemplate.executeWithoutResult(tx -> {
                ReviewJob job = jobRepository.lockById(jobId).orElseThrow();
                change.accept(job);
                jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
                locked.countDown();
                awaitBlockedTransaction();
                if (!commit) tx.setRollbackOnly();
            }));
            var waiting = pool.submit(() -> {
                assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
                contender.run();
                return null;
            });
            holder.get(30, TimeUnit.SECONDS);
            waiting.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitBlockedTransaction() {
        Integer holderPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        Awaitility.await().pollInSameThread().atMost(30, TimeUnit.SECONDS).until(() -> {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE CAST(? AS integer) ="
                            + " ANY(pg_blocking_pids(pid)))",
                    Boolean.class,
                    holderPid));
        });
    }

    private boolean continueComposition(ReviewJob job, String workerId) {
        return continueComposition(job, workerId, null, null);
    }

    private boolean continueComposition(ReviewJob job, String workerId, AgentResult.@Nullable LlmUsage runnerUsage) {
        return continueComposition(job, workerId, runnerUsage, null);
    }

    private boolean continueComposition(
            ReviewJob job,
            String workerId,
            AgentResult.@Nullable LlmUsage runnerUsage,
            byte @Nullable [] scriptReport) {
        return continueComposition(job, workerId, runnerUsage, scriptReport, null);
    }

    private boolean continueComposition(ReviewJob job, String workerId, byte[] feedbackReport) {
        return continueComposition(job, workerId, null, null, feedbackReport);
    }

    private boolean continueComposition(
            ReviewJob job,
            String workerId,
            AgentResult.@Nullable LlmUsage runnerUsage,
            byte @Nullable [] scriptReport,
            byte @Nullable [] feedbackReport) {
        return Boolean.TRUE.equals(transactionTemplate.execute(
                status -> recovery.continueComposition(job, workerId, runnerUsage, scriptReport, feedbackReport)));
    }

    // The runner's feedback report, as it writes it before asking for the attempt to continue.
    private static byte[] feedbackReport(String admissionDigest, String... failures) {
        return ("{\"compositionFailures\":[" + String.join(",", failures) + "],\"contractVersion\":3,"
                        + "\"admissionDigest\":\"" + admissionDigest
                        + "\",\"results\":[],\"feedback\":[],\"review\":null}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String failure(String phase, String reason) {
        return "{\"phase\":\"" + phase + "\",\"reason\":\"" + reason + "\"}";
    }

    private static byte[] publicStop(String reason) {
        return feedbackReport("admitted-digest", failure("PUBLIC_REVIEW", reason));
    }

    // A second pinned practice that the capture refused: the runner was never given it.
    private Practice withCaptureRefusedSibling(UUID jobId) {
        Practice sibling = runtimePracticeVersions.create(new PracticeVersionService.NewPractice(
                workspace,
                "capture-refused-sibling",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(
                        new ItemKind("scm.pull_request"), "Refused sibling", "A sibling definition", null),
                null,
                0,
                null));
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            long itemId = job.getInput().itemId();
            jdbc.update(
                    "INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status) VALUES"
                            + " (?,?,?,?,'READY')",
                    job.getReviewId(),
                    workspace.getId(),
                    sibling.getId(),
                    sibling.getCurrentVersion().getId());
            jdbc.update(
                    "INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version) VALUES"
                            + " (?,?,?,?,'fixture:one')",
                    job.getReviewId(),
                    workspace.getId(),
                    sibling.getId(),
                    itemId);
            job.setInput(new ReviewJobInput(
                    itemId,
                    List.of(
                            new ReviewAssignmentId(job.getReviewId(), practice.getId(), itemId),
                            new ReviewAssignmentId(job.getReviewId(), sibling.getId(), itemId))));
            job.setCapture(objectMapper.readTree("{\"manifest\":{\"contractVersion\":\"2.0.0\"},\"readiness\":"
                    + "{\"decisions\":[{\"practiceSlug\":\"" + practice.getSlug() + "\",\"ready\":true},"
                    + "{\"practiceSlug\":\"" + sibling.getSlug() + "\",\"ready\":false}]}}"));
            jobRepository.saveAndFlush(job);
        });
        return sibling;
    }

    private void pinScriptAssignments(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            var binding = agentBindingRepository
                    .findByWorkspaceIdAndPurpose(workspace.getId(), AgentPurpose.PRACTICE_REVIEW)
                    .getFirst();
            var frozen = methods.freeze(binding);
            var slots = List.of(
                    new BoundModelSlot("review", ModelKind.CHAT, frozen),
                    new BoundModelSlot("chat", ModelKind.CHAT, null),
                    new BoundModelSlot("decision", ModelKind.DECISION, null),
                    new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                    new BoundModelSlot("reranking", ModelKind.RERANKING, null));
            String digest = requireNonNull(jdbc.queryForObject(
                    "SELECT review_model_digest(CAST(? AS jsonb))",
                    String.class,
                    objectMapper.writeValueAsString(slots)));
            jdbc.update(
                    "UPDATE review_practice SET method=CAST(? AS jsonb) WHERE workspace_id=? AND review_id=?",
                    objectMapper.writeValueAsString(new ReviewMethod(1, digest, slots)),
                    workspace.getId(),
                    job.getReviewId());
            jdbc.update(
                    "UPDATE review_item SET detail=jsonb_build_object('developerId',?) WHERE workspace_id=? AND review_id=?",
                    developer.getId(),
                    workspace.getId(),
                    job.getReviewId());
        });
    }

    private static byte[] scriptReport(String... slugs) {
        String entries = Arrays.stream(slugs)
                .map(slug -> "{\"practiceSlug\":\"" + slug + "\",\"program\":{\"kind\":\"builtin\",\"digest\":null},"
                        + "\"models\":[],\"status\":\"ok\",\"stopReason\":null,\"calls\":{\"review\":1,\"chat\":0,"
                        + "\"decision\":0,\"embedding\":0,\"reranking\":0},\"coverageReached\":{\"reads\":0,"
                        + "\"truncatedReads\":0,\"failedReads\":0},\"durationMs\":1}")
                .collect(Collectors.joining(","));
        return ("{\"schemaVersion\":2,\"truncated\":false,\"practices\":[" + entries + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void acceptPublicReview(UUID jobId) {
        jdbc.update("UPDATE review_job SET public_review_digest = ? WHERE id = ?", "b".repeat(64), jobId);
    }

    private List<@Nullable Integer> usageCalls(UUID jobId) {
        return jdbc.queryForList(
                "SELECT total_calls FROM llm_usage_event WHERE source_id::text = ?", Integer.class, jobId.toString());
    }

    private Result admitWithResult(UUID jobId) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            UUID id = UUID.randomUUID();
            var row = Result.builder()
                    .id(id)
                    .workspaceId(job.getWorkspaceId())
                    .reviewId(job.getReviewId())
                    .practiceId(practice.getId())
                    .practiceVersionId(practice.getCurrentVersion().getId())
                    .itemId(job.getInput().itemId())
                    .itemVersion("fixture:one")
                    .developerId(null)
                    .outcome(ResultOutcome.MET)
                    .summary("Stored result")
                    .explanation("The recorded quote supports it.")
                    .evidence(AdmittedResultFixtures.evidence("scm.pull-request.core"))
                    .coverage(objectMapper.readTree(
                            "{\"source\":\"claimed\",\"complete\":true,\"selected\":1,\"checked\":1,\"total\":1}"))
                    .recordedAt(Instant.now())
                    .build();
            practiceResultRepository.saveAndFlush(row);
            jdbc.update(
                    "UPDATE review_item SET state='JUDGED',result_id=?,job_id=? WHERE workspace_id=? AND review_id=?"
                            + " AND practice_id=? AND item_id=?",
                    row.getId(),
                    job.getId(),
                    job.getWorkspaceId(),
                    job.getReviewId(),
                    practice.getId(),
                    job.getInput().itemId());
            job.setAdmissionDigest("admitted-digest");
            job.setAdmissionAttempt(job.getAttempt());
            job.setCapture(objectMapper
                    .createObjectNode()
                    .set("manifest", objectMapper.createObjectNode().put("contractVersion", "2.0.0")));
            jobRepository.saveAndFlush(job);
            return row;
        }));
    }

    private void keepOriginalInputs(
            UUID jobId, int attempt, String receiptState, String dependencies, Instant expiresAt) {
        keepOriginalInputs(jobId, attempt, receiptState, dependencies, expiresAt, Map.of());
    }

    private void keepOriginalInputs(
            UUID jobId,
            int attempt,
            String receiptState,
            String dependencies,
            Instant expiresAt,
            Map<String, byte[]> files) {
        UUID copyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO person_evidence_copy(id,workspace_id,job_id,store_id,state,payload) "
                        + "VALUES (?,?,?,?,?,CAST(? AS jsonb))",
                copyId,
                workspace.getId(),
                jobId,
                UUID.randomUUID(),
                receiptState,
                objectMapper
                        .createObjectNode()
                        .put("attempt", attempt)
                        .put("dependencies", dependencies)
                        .set(
                                "identities",
                                objectMapper.valueToTree(
                                        List.of(new PersonCopyIdentity("GITHUB", "https://github.com", "100", null))))
                        .toString());
        var basis = new ReviewCompositionBasis();
        basis.setJobId(jobId);
        basis.setWorkspaceId(workspace.getId());
        basis.setAdmissionAttempt(attempt);
        basis.setAdmissionDigest("admitted-digest");
        basis.setBasisVersion(ReviewCompositionBasis.VERSION);
        basis.setBasisDigest(ReviewCompositionBasis.digest(files));
        basis.setCopyId(copyId);
        basis.setExpiresAt(expiresAt);
        var retained = objectMapper.createObjectNode();
        files.forEach((path, bytes) -> retained.put(path, Base64.getEncoder().encodeToString(bytes)));
        basis.setPayload(objectMapper.createObjectNode().set("files", retained));
        transactionTemplate.executeWithoutResult(status -> compositionBasisRepository.save(basis));
    }

    // What an admission retains of its attempt's folder: the manifest and its index, a context record, the practice
    // index and the composition request.
    private Map<String, byte[]> admittedInputs() {
        Instant now = Instant.now();
        var source = new SourceKind("scm.pull-request.diff");
        var manifest = new JobFolderIndex(
                new SourceContractVersion("2.0.0"),
                "a".repeat(64),
                "scm.pull_request",
                now,
                List.of(new SourceCapture(
                        source,
                        new SourceCaptureState.Available(
                                SourceContentState.EMPTY,
                                SourceCompleteness.COMPLETE,
                                new SourceCaptureFacts(now, null, null, "base:head")),
                        EvidenceSnapshotFixtures.citation(source),
                        List.of())),
                List.of(),
                List.of(),
                List.of());
        return Map.of(
                SandboxLayout.MANIFEST_PATH,
                objectMapper.writeValueAsBytes(manifest),
                "INDEX.md",
                "# Captured inputs\n".getBytes(StandardCharsets.UTF_8),
                SandboxLayout.CONTEXT_PREFIX + "metadata.json",
                "{\"title\":\"Fixture\"}".getBytes(StandardCharsets.UTF_8),
                SandboxLayout.PRACTICES_PREFIX + "index.json",
                "[]".getBytes(StandardCharsets.UTF_8),
                SandboxLayout.FEEDBACK_COMPOSITION_PATH,
                "{\"destinations\":[\"PRACTICE_PAGE\"]}".getBytes(StandardCharsets.UTF_8));
    }

    private void spentBeforeItWasLost(UUID jobId, int calls) {
        jdbc.update("UPDATE review_job SET execution_started_at = now(), llm_calls = ? WHERE id = ?", calls, jobId);
    }

    private void changePracticeDefinition() {
        var kind = practice.getKind();
        runtimePracticeVersions.append(
                practice,
                DefinitionTestFixtures.definition(kind, "Updated standard", "The second definition", null),
                null);
    }

    private UUID runningJobOwnedBy(String workerId, Instant startedAt, int attempt) {
        return runningJobOwnedBy(workerId, startedAt, attempt, null);
    }

    private UUID runningJobOwnedBy(String workerId, Instant startedAt, int attempt, @Nullable ItemRef preparedWork) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://api.openai.com/v1",
                                "test-model",
                                UsageFundingSource.INSTANCE,
                                instanceModel.getConnection().getId(),
                                instanceModel.getId(),
                                workspace.getId(),
                                null,
                                null,
                                null),
                        null,
                        null,
                        600,
                        false,
                        null)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE, UsagePricingState.NO_CHARGE, null, null, null, null, null, null))
                .toJson(objectMapper));
        job.setWorkerId(workerId);
        job.setStartedAt(startedAt);
        job.setAttempt(attempt);
        long itemId;
        String itemVersion;
        if (preparedWork == null) {
            itemId = requireNonNull(jdbc.queryForObject(
                    "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,?,?,now()) RETURNING id",
                    Long.class,
                    workspace.getId(),
                    practice.getKind().value(),
                    (job.getReviewId().getLeastSignificantBits() & Long.MAX_VALUE) | 1));
            itemVersion = "fixture:one";
        } else {
            assertThat(preparedWork.workspaceId()).isEqualTo(workspace.getId());
            assertThat(preparedWork.kind()).isEqualTo(practice.getKind());
            itemId = preparedWork.itemId();
            itemVersion = privateItemSource.pin(preparedWork, null).version().value();
        }
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,ordered_at) VALUES"
                        + " (?,?,?,'ITEM',?,'REQUEST',now())",
                job.getReviewId(),
                workspace.getId(),
                itemId,
                itemVersion);
        jdbc.update(
                "INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status) VALUES"
                        + " (?,?,?,?,'READY')",
                job.getReviewId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentVersion().getId());
        jdbc.update(
                "INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version) VALUES"
                        + " (?,?,?,?,?)",
                job.getReviewId(),
                workspace.getId(),
                practice.getId(),
                itemId,
                itemVersion);
        job.setInput(new ReviewJobInput(
                itemId, List.of(new ReviewAssignmentId(job.getReviewId(), practice.getId(), itemId))));
        return jobRepository.saveAndFlush(job).getId();
    }

    private void registerStaleWorker(String workerId) {
        // The orphan query judges the lease on the database clock.
        Instant lastHeartbeat = requireNonNull(jdbc.queryForObject(
                        "SELECT now() - make_interval(secs => ?)",
                        OffsetDateTime.class,
                        AgentProperties.WORKER_LEASE_TTL.plusMinutes(1).toSeconds()))
                .toInstant();
        WorkerRegistry w = new WorkerRegistry();
        w.setWorkerId(workerId);
        w.setLastHeartbeat(lastHeartbeat);
        w.setRegisteredAt(lastHeartbeat);
        workerRegistryRepository.saveAndFlush(w);
    }

    private boolean eligibleForClaim(UUID jobId) {
        return transactionTemplate.execute(status -> jobRepository
                .findByIdQueuedForUpdateSkipLocked(jobId, Instant.now())
                .isPresent());
    }

    private void fastForwardAvailableAt(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setAvailableAt(Instant.now().minus(Duration.ofSeconds(1)));
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
    }
}
