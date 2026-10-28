package de.tum.cit.aet.hephaestus.agent.job;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackCompositionService;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrepareFeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.worker.WorkerProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxReconciler;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxCapacityException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    private ReviewRuntimeRepository runtimeRepository;

    @Autowired
    private ReviewAssignmentStore members;

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

    @Autowired
    private ResultAdmissionService admissions;

    @Autowired
    private ReviewService reviews;

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
        // A tiny renewal interval: the quiet time after the priming check ends at once, and the cutoff is now.
        when(properties.heartbeatInterval()).thenReturn(Duration.ofMillis(1));
        recovery =
                new ReviewJobRecovery(jobRepository, properties, objectMapper, attemptUsage, Optional.of(continuation));
        sweeper = new ReviewJobZombieSweeper(
                jobRepository,
                workerRegistryRepository,
                objectMapper,
                transactionTemplate,
                attemptUsage,
                meterRegistry,
                new ReviewJobTelemetry(meterRegistry, Tracer.NOOP),
                recovery,
                properties);
        // The first lease check after a start reaps nothing: each test's check then follows a previous one.
        sweeper.recoverOrphanedJobs();
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
        expireLeasesOf("dead-replica");

        sweeper.recoverOrphanedJobs();

        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getWorkerId()).isNull();
        assertThat(requeued.getAttempt()).isEqualTo(1);

        fastForwardAvailableAt(jobId);

        assertThat(eligibleForClaim(jobId)).isTrue();
    }

    @Test
    void shouldRemoveTheLostAttemptsSandboxOnceTheJobIsRequeuedAndKeepTheNewAttempts() {
        UUID jobId = runningJobOwnedBy("dead-replica", Instant.now().minus(Duration.ofMinutes(5)), 0);
        expireLeasesOf("dead-replica");
        List<SandboxBackend.Listed> sandboxes = new ArrayList<>();
        sandboxes.add(reviewSandbox("attempt-0", jobId, 0));
        SandboxBackend backend = mock(SandboxBackend.class);
        when(backend.list()).thenAnswer(invocation -> List.copyOf(sandboxes));
        doAnswer(invocation -> sandboxes.removeIf(sandbox -> sandbox.handle().equals(invocation.getArgument(0))))
                .when(backend)
                .remove(any());
        var reconciler = new SandboxReconciler(jobRepository, backend, meterRegistry, Clock.systemUTC());

        sweeper.recoverOrphanedJobs();
        sandboxes.add(reviewSandbox("attempt-1", jobId, 1));
        reconciler.periodicReconciliation();

        assertThat(jobRepository.findById(jobId).orElseThrow().getAttempt()).isEqualTo(1);
        assertThat(sandboxes).extracting(sandbox -> sandbox.handle().id()).containsExactly("attempt-1");
    }

    private static SandboxBackend.Listed reviewSandbox(String name, UUID jobId, int attempt) {
        return new SandboxBackend.Listed(
                new SandboxBackend.Handle(name), SandboxBackend.Kind.REVIEW, jobId, attempt, Instant.now(), false);
    }

    @Test
    @DisplayName("a direct claim attempt made WHILE still backed off does not succeed")
    void claimAttemptWhileStillBackedOffDoesNotSucceed() {
        UUID jobId = runningJobOwnedBy("dead-replica-5", Instant.now().minus(Duration.ofMinutes(5)), 0);
        expireLeasesOf("dead-replica-5");

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
        expireLeasesOf("dead-replica-4");

        sweeper.recoverOrphanedJobs();

        // The requeue's backoff puts available_at far enough out that this is not a race with the clock.
        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getAvailableAt()).isAfter(Instant.now());

        assertThat(jobRepository.findQueuedIdsStartedFirst(10))
                .as("a not-yet-eligible QUEUED job must not be offered as a poll candidate")
                .doesNotContain(jobId);
    }

    @Test
    @DisplayName("an orphan already at the retry cap is failed, not requeued again")
    void orphanPastRetryCapIsFailedNotRequeued() {
        UUID jobId = runningJobOwnedBy("dead-replica-2", Instant.now().minus(Duration.ofMinutes(5)), 5);
        expireLeasesOf("dead-replica-2");

        sweeper.recoverOrphanedJobs();

        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("retry limit is reached");
        assertThat(jobRepository.findQueuedIdsStartedFirst(10)).doesNotContain(jobId);
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
    @DisplayName(
            "an orphan whose results were admitted and whose review retained no inputs is handed on with them kept,"
                    + " after its practice changed")
    void shouldHandOnALostAdmittedReviewThatRetainedNoInputsWithItsResultsKept() {
        UUID jobId = runningJobOwnedBy("dead-replica-6", Instant.now().minus(Duration.ofMinutes(5)), 0);
        Result observed = admitWithResult(jobId);
        changePracticeDefinition();
        expireLeasesOf("dead-replica-6");
        ReviewJob before = jobRepository.findById(jobId).orElseThrow();

        sweeper.recoverOrphanedJobs();

        // The next attempt measures nothing: it withholds each destination still open, so no retry fails the review.
        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(ResultAdmissionService.isContinuation(failed)).isTrue();
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
                s -> jobRepository.completeAdmittedOwnedBy(admitted, "stranger", 0, Instant.now()));
        int byLaterAttempt = transactionTemplate.execute(
                s -> jobRepository.completeAdmittedOwnedBy(admitted, "owner", 1, Instant.now()));
        int notAdmitted = transactionTemplate.execute(
                s -> jobRepository.completeAdmittedOwnedBy(unadmitted, "owner", 0, Instant.now()));

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
        expireLeasesOf("dead-replica-9");
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
        // The lost worker's own attempt is gone: its requeue or its completion matches nothing now.
        Integer staleRequeue = transactionTemplate.execute(s -> {
            continuation.holdRecoveryCustody();
            return continuation.requeue(
                    continuing, "dead-replica-9", new ReviewJobRepository.CompositionRequeue(0, 5, Instant.now()));
        });
        assertThat(staleRequeue).isZero();
        Integer staleCompletion = transactionTemplate.execute(
                s -> jobRepository.completeAdmittedOwnedBy(jobId, "dead-replica-9", 0, Instant.now()));
        assertThat(staleCompletion).isZero();
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
    @DisplayName(
            "a lost admitted review whose original inputs cannot be continued is handed on, and its next attempt decides"
                    + " what to withhold")
    void shouldHandOnTheLostAdmittedReviewWhenItsOriginalInputsCannotBeContinued(String why) {
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
        expireLeasesOf("dead-replica-10");

        sweeper.recoverOrphanedJobs();

        ReviewJob handedOn = jobRepository.findById(jobId).orElseThrow();
        assertThat(handedOn.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(handedOn.getAttempt()).isEqualTo(1);
        assertThat(ResultAdmissionService.isContinuation(handedOn)).isTrue();
        assertThat(jdbc.queryForList(
                        "SELECT total_calls FROM llm_usage_event WHERE source_id::text = ? AND source_attempt = 0",
                        Integer.class,
                        jobId.toString()))
                .containsExactly(2);
    }

    @Test
    @DisplayName("an admitted review lost at the retry limit gets one more attempt, which only withholds what is open")
    void shouldHandOnceMoreAnAdmittedReviewLostAtTheRetryLimit() {
        UUID jobId = runningJobOwnedBy("dead-replica-11", Instant.now().minus(Duration.ofMinutes(5)), 5);
        admitWithResult(jobId);
        expireLeasesOf("dead-replica-11");

        sweeper.recoverOrphanedJobs();

        ReviewJob closing = jobRepository.findById(jobId).orElseThrow();
        assertThat(closing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(closing.getAttempt()).isEqualTo(6);
        assertThat(ResultAdmissionService.isContinuation(closing)).isTrue();
    }

    @Test
    @DisplayName("an admitted review whose last attempt is lost too completes with its results kept and never fails")
    void shouldCompleteAnAdmittedReviewWhoseAttemptAfterTheRetryLimitIsLost() {
        UUID jobId = runningJobOwnedBy("dead-replica-12", Instant.now().minus(Duration.ofMinutes(5)), 6);
        Result observed = admitWithResult(jobId);
        expireLeasesOf("dead-replica-12");
        var unrecorded = meterRegistry.counter(
                AgentMetrics.AGENT_JOB_COMPOSITION_STOPPED, "cause", "retries_exhausted", "outcome", "unrecorded");
        double unrecordedBefore = unrecorded.count();

        sweeper.recoverOrphanedJobs();

        ReviewJob completed = jobRepository.findById(jobId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
        assertThat(unrecorded.count() - unrecordedBefore).isEqualTo(1);
        assertThat(completed.getErrorMessage()).isNull();
        assertThat(practiceResultRepository.findById(observed.getId())).isPresent();
    }

    @Test
    @DisplayName(
            "prepared private pieces decide their practices per destination, a not-composed withholding included, and"
                    + " a replayed package stores nothing twice")
    void shouldReadPrivateProgressPerDestinationAndReplayALostReceipt() {
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
        Result carded = admitPrivateNegative(jobId, practice, accountId);
        Result open = admitPrivateNegative(jobId, sibling, accountId);
        acceptPublicReview(jobId);
        assertThat(reviews.start(
                        workspace.getId(),
                        jobRepository.findById(jobId).orElseThrow().getReviewId()))
                .isTrue();

        UUID card =
                preparePrivately(jobId, privateQuietDecision(FeedbackDestination.PRACTICE_PAGE, carded), carded, open);
        // The same package again, as a resumed attempt would send it after the first answer was lost.
        assertThat(preparePrivately(
                        jobId, privateQuietDecision(FeedbackDestination.PRACTICE_PAGE, carded), carded, open))
                .isEqualTo(card);
        UUID notComposed = preparePrivately(jobId, notComposed(FeedbackDestination.CONVERSATION, open), carded, open);

        var progress = admissions.privateProgress(jobRepository.findById(jobId).orElseThrow());
        // The page card closes its practice on the page only; the conversation is still open for it.
        assertThat(progress.decidedByDestination())
                .containsOnly(
                        Map.entry(FeedbackDestination.PRACTICE_PAGE, Set.of(carded.getId())),
                        Map.entry(FeedbackDestination.CONVERSATION, Set.of(open.getId())));
        assertThat(progress.practicePageDeliveries()).isZero();
        assertThat(progress.conversationDeliveries()).isZero();
        // Preparation re-decides each destination, so its own refusal may replace the composer's reason.
        assertThat(jdbc.queryForList(
                        "SELECT state FROM feedback WHERE workspace_id=? AND id IN (?,?)",
                        String.class,
                        workspace.getId(),
                        card,
                        notComposed))
                .containsExactly("WITHHELD", "WITHHELD");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM feedback WHERE workspace_id=? AND id=?",
                        Integer.class,
                        workspace.getId(),
                        card))
                .isEqualTo(1);
    }

    private UUID preparePrivately(UUID jobId, ComposedFeedback piece, Result... admitted) {
        var rows = Arrays.stream(admitted).map(privateProof::snapshot).toList();
        var accepted = requireNonNull(transactionTemplate.execute(status -> {
            ReviewJob owned = jobRepository.lockById(jobId).orElseThrow();
            var receipt = privateComposition.prepare(
                    owned,
                    new PrepareFeedbackRequestDTO(
                            owned.getReviewId(),
                            jobId,
                            0,
                            Arrays.stream(admitted).map(Result::getId).toList(),
                            List.of(piece),
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
        return accepted.feedbackIds().getFirst();
    }

    private static ComposedFeedback notComposed(FeedbackDestination destination, Result result) {
        return new ComposedFeedback(
                destination,
                List.of(result.getId()),
                null,
                List.of(),
                FeedbackStopReason.NOT_COMPOSED,
                new FeedbackCompositionTrace(
                        List.of(),
                        List.of(new FeedbackCompositionTrace.QuietDecision(
                                List.of(new FeedbackCompositionTrace.Support(result.getId(), null)),
                                FeedbackCompositionTrace.QuietDecision.NOT_COMPOSED,
                                List.of())),
                        null,
                        result.getId()),
                null);
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
                    .set("manifest", objectMapper.createObjectNode().put("contractVersion", "3.0.0")));
            jobRepository.saveAndFlush(job);
            return row;
        }));
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
        expireLeasesOf("dead-replica-11");

        sweeper.recoverOrphanedJobs();

        // The new attempt's admission answer says which practices the prepared pieces decide; it composes the rest.
        ReviewJob continuing = jobRepository.findById(jobId).orElseThrow();
        assertThat(continuing.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(continuing.getAttempt()).isEqualTo(1);
        assertThat(continuing.getPublicReviewDigest()).isEqualTo("b".repeat(64));
        assertThat(continuing.getPracticePagePreparedAt() != null).isEqualTo(privatePiece);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not admitted", "public review not accepted", "expired inputs", "retry limit reached"})
    @DisplayName("a timed-out run ends timed out after its composition and drops its retained inputs")
    void shouldEndATimedOutRunAsTimedOutAndDropItsRetainedInputs(String why) {
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

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = ReviewJobCancellationReason.class,
            names = {"DRAIN_GRACEFUL", "DRAIN_IMMEDIATE"})
    @DisplayName("a drained review runs again under a new attempt number without spending a retry")
    void shouldRequeueADrainedReviewUnderANewAttemptWithoutSpendingARetry(ReviewJobCancellationReason drain) {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 2);

        ReviewJobRecovery.Outcome outcome =
                transactionTemplate.execute(status -> recovery.recover(jobId, "live-owner", 2, drain));
        assertThat(outcome).isEqualTo(ReviewJobRecovery.Outcome.REQUEUED);

        ReviewJob requeued = jobRepository.findById(jobId).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(requeued.getAttempt())
                .as("the drained attempt ran, so the next one has its own number")
                .isEqualTo(3);
        assertThat(requeued.getDrainCount()).isEqualTo(1);
        assertThat(requeued.getWorkerId()).isNull();
    }

    @Test
    @DisplayName("a review drained at its retry limit still runs again, and a later failure counts only spent retries")
    void shouldRequeueADrainedReviewAtItsRetryLimitAndCountOnlySpentRetriesAfterwards() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 5);

        assertThat(recoverAfterDrain(jobId, 5)).isEqualTo(ReviewJobRecovery.Outcome.REQUEUED);
        claimAgain(jobId, "live-owner");

        ReviewJobRecovery.Outcome lost =
                transactionTemplate.execute(status -> recovery.recover(jobId, "live-owner", 6, null));
        assertThat(lost)
                .as("the worker was lost: 5 retries spent of 5, so the review fails")
                .isEqualTo(ReviewJobRecovery.Outcome.FAILED);
        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getAttempt()).isEqualTo(6);
        assertThat(failed.getDrainCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a sandbox that its node evicts again and again spends a retry each time, unbilled, until the limit")
    void shouldBoundTheRetriesOfASandboxThatIsEvictedEveryTime() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 4);

        // An eviction before the sandbox ran: no model call to bill, as for a workspace that overfills its volume.
        Boolean retried = transactionTemplate.execute(
                status -> recovery.retryUnadmitted(jobRepository.findById(jobId).orElseThrow(), "live-owner", false));
        assertThat(retried).isTrue();
        assertThat(jobRepository.findById(jobId).orElseThrow().getAttempt()).isEqualTo(5);
        claimAgain(jobId, "live-owner");

        Boolean again = transactionTemplate.execute(
                status -> recovery.retryUnadmitted(jobRepository.findById(jobId).orElseThrow(), "live-owner", false));
        assertThat(again)
                .as("5 retries spent of 5: the next eviction fails the review")
                .isFalse();
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    @DisplayName(
            "a review runs again after its last allowed drain, and the drain after it cancels it with the drain's reason")
    void shouldCancelAReviewWithTheDrainsReasonOnceItWasDrainedTooOften() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 3);
        withCounts(jobId, ReviewJob.MAX_DRAINS - 1, 0);

        assertThat(recoverAfterDrain(jobId, 3)).isEqualTo(ReviewJobRecovery.Outcome.REQUEUED);
        claimAgain(jobId, "live-owner");
        assertThat(recoverAfterDrain(jobId, 4)).isEqualTo(ReviewJobRecovery.Outcome.CANCELLED);

        ReviewJob cancelled = jobRepository.findById(jobId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.DRAIN_GRACEFUL);
        assertThat(cancelled.getErrorMessage()).isEqualTo(ReviewJobRecovery.DRAINED_TOO_OFTEN);
        assertThat(cancelled.getAttempt()).isEqualTo(4);
        assertThat(cancelled.getDrainCount()).isEqualTo(ReviewJob.MAX_DRAINS);
    }

    @Test
    @DisplayName("an admitted review that a drain stops is handed on as before, without a drain count")
    void shouldHandOnADrainedAdmittedReviewWithoutCountingTheDrain() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(jobId);
        acceptPublicReview(jobId);
        keepOriginalInputs(jobId, 0, "READY", "COMPLETE", Instant.now().plus(Duration.ofHours(1)));

        assertThat(recoverAfterDrain(jobId, 0)).isEqualTo(ReviewJobRecovery.Outcome.REQUEUED);

        ReviewJob handedOn = jobRepository.findById(jobId).orElseThrow();
        assertThat(handedOn.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(handedOn.getAttempt()).isEqualTo(1);
        assertThat(handedOn.getDrainCount()).isZero();
    }

    @Test
    @DisplayName("a review whose sandbox the quota has no room for waits in the queue under a new attempt number")
    void shouldHoldAReviewUnderANewAttemptWhenTheSandboxQuotaHasNoRoom() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 2);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();
        double counted = sandboxStartFailures(SandboxCapacityException.Reason.QUOTA_EXCEEDED);

        assertThat(executorOwnedBy("live-owner", refusedSandbox(SandboxCapacityException.Reason.QUOTA_EXCEEDED))
                        .executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isNull();

        ReviewJob held = jobRepository.findById(jobId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(held.getAttempt())
                .as("nothing of this attempt is reused: its evidence, Job name and token stay its own")
                .isEqualTo(3);
        assertThat(held.getRoomRefusals())
                .as("the refusal is counted instead of a retry")
                .isEqualTo(1);
        assertThat(held.getWorkerId()).isNull();
        assertThat(held.getHoldReason()).isEqualTo(ReviewJob.HOLD_REASON_SANDBOX_ROOM);
        assertThat(held.getAvailableAt()).isAfter(Instant.now());
        assertThat(eligibleForClaim(jobId)).as("it waits before the next claim").isFalse();
        assertThat(sandboxStartFailures(SandboxCapacityException.Reason.QUOTA_EXCEEDED))
                .isEqualTo(counted + 1);
    }

    @Test
    @DisplayName("a review that the quota refused many times waits again: only starvation ends it")
    void shouldHoldAReviewAgainHoweverOftenTheSandboxQuotaHadNoRoom() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 40);
        withCounts(jobId, 0, 40);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();

        assertThat(executorOwnedBy("live-owner", refusedSandbox(SandboxCapacityException.Reason.QUOTA_EXCEEDED))
                        .executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isNull();

        ReviewJob held = jobRepository.findById(jobId).orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(held.getRoomRefusals()).isEqualTo(41);
        assertThat(held.spentAttempts()).isZero();
    }

    @Test
    @DisplayName("a review whose sandbox no quota room can ever hold is cancelled for capacity with its own message")
    void shouldCancelAReviewWhoseSandboxTheQuotaCanNeverHold() {
        UUID jobId = runningJobOwnedBy("live-owner", Instant.now(), 1);
        ReviewJob running = jobRepository.findById(jobId).orElseThrow();
        double counted = sandboxStartFailures(SandboxCapacityException.Reason.QUOTA_CANNOT_FIT);

        assertThat(executorOwnedBy("live-owner", refusedSandbox(SandboxCapacityException.Reason.QUOTA_CANNOT_FIT))
                        .executePrepared(jobId, running, mock(SandboxSpec.class)))
                .isEqualTo(ReviewJobStatus.CANCELLED);

        ReviewJob failed = jobRepository.findById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(failed.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.NO_SANDBOX_ROOM);
        assertThat(failed.getExecutionStartedAt())
                .as("nothing of the sandbox ran")
                .isNull();
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getErrorMessage()).isEqualTo("The sandbox quota is smaller than one review sandbox.");
        assertThat(sandboxStartFailures(SandboxCapacityException.Reason.QUOTA_CANNOT_FIT))
                .isEqualTo(counted + 1);
    }

    @Test
    @DisplayName("the queue counts a review that waits for sandbox room as waiting, and only a budget hold as held")
    void shouldCountASandboxRoomHoldAsWaitingAndOnlyABudgetHoldAsHeld() {
        UUID room = runningJobOwnedBy("live-owner", Instant.now(), 0);
        UUID budget = runningJobOwnedBy("live-owner", Instant.now(), 0);
        heldBy(room, ReviewJob.HOLD_REASON_SANDBOX_ROOM);
        heldBy(budget, ReviewJob.HOLD_REASON_BUDGET);

        ReviewRuntimeRepository.QueueCounts counts = runtimeRepository.queueCounts(workspace.getId(), Instant.now());

        assertThat(counts.waiting())
                .as("this test's workspace holds only its own two reviews")
                .isEqualTo(1);
        assertThat(counts.held()).isEqualTo(1);
    }

    /** The job waits in the queue on the hold. */
    private void heldBy(UUID jobId, String holdReason) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(ReviewJobStatus.QUEUED);
            job.setWorkerId(null);
            job.setHoldReason(holdReason);
            job.setAvailableAt(Instant.now().plus(Duration.ofMinutes(5)));
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
    }

    @Test
    @DisplayName("queued reviews that no sandbox room can ever hold are cancelled; composing and budget-held ones stay")
    void shouldCancelQueuedReviewsWhenNoSandboxRoomCanEverHoldThem() {
        UUID waiting = queuedAndDue(runningJobOwnedBy("live-owner", Instant.now(), 0));
        UUID composing = runningJobOwnedBy("live-owner", Instant.now(), 0);
        admitWithResult(composing);
        queuedAndDue(composing);
        UUID budgetHeld = queuedAndDue(runningJobOwnedBy("live-owner", Instant.now(), 0));
        // Its hold has run out, and no claim has cleared it yet.
        jdbc.update("UPDATE review_job SET hold_reason = 'BUDGET' WHERE id = ?", budgetHeld);
        var sandbox = mock(SandboxManager.class);
        when(sandbox.neverFits(any())).thenReturn(true);

        executorOwnedBy("live-owner", sandbox).endReviewsWithoutRoom(Instant.now());

        ReviewJob cancelled = jobRepository.findById(waiting).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.NO_SANDBOX_ROOM);
        assertThat(cancelled.getErrorMessage()).isEqualTo("The sandbox quota is smaller than one review sandbox.");
        assertThat(jobRepository.findById(composing).orElseThrow().getStatus())
                .as("composition needs no sandbox")
                .isEqualTo(ReviewJobStatus.QUEUED);
        ReviewJob held = jobRepository.findById(budgetHeld).orElseThrow();
        assertThat(held.getStatus())
                .as("a budget hold waits for money, not room")
                .isEqualTo(ReviewJobStatus.QUEUED);
        assertThat(held.getHoldReason()).isEqualTo(ReviewJob.HOLD_REASON_BUDGET);
    }

    @Test
    @DisplayName("queued reviews end after an hour without room while no review runs, and never behind running reviews")
    void shouldEndQueuedReviewsAfterAnHourOfStarvationButNeverForBacklog() {
        UUID backlog = queuedAndDue(runningJobOwnedBy("live-owner", Instant.now(), 0));
        var busy = mock(SandboxManager.class);
        when(busy.reviewsRunning()).thenReturn(true);
        var behindRunningReviews = executorOwnedBy("live-owner", busy);
        Instant start = Instant.now();
        behindRunningReviews.endReviewsWithoutRoom(start);
        behindRunningReviews.endReviewsWithoutRoom(start.plus(Duration.ofHours(2)));
        assertThat(jobRepository.findById(backlog).orElseThrow().getStatus())
                .as("waiting behind running reviews is backlog")
                .isEqualTo(ReviewJobStatus.QUEUED);

        var starved = executorOwnedBy("live-owner", mock(SandboxManager.class));
        starved.endReviewsWithoutRoom(start);
        assertThat(jobRepository.findById(backlog).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        starved.endReviewsWithoutRoom(start.plus(Duration.ofMinutes(61)));

        ReviewJob ended = jobRepository.findById(backlog).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
        assertThat(ended.getCancellationReason()).isEqualTo(ReviewJobCancellationReason.NO_SANDBOX_ROOM);
        assertThat(ended.getErrorMessage()).isEqualTo("Hephaestus had no room to run this review in time.");
    }

    @Test
    @DisplayName("a review cancelled for sandbox room keeps that reason when it settles, claimed or still queued")
    void shouldRecordNoSandboxRoomOnTheReviewWhenSettled() {
        UUID claimed = withoutAssignments(runningJobOwnedBy("live-owner", Instant.now(), 1));
        ReviewJob running = jobRepository.findById(claimed).orElseThrow();
        executorOwnedBy("live-owner", refusedSandbox(SandboxCapacityException.Reason.QUOTA_CANNOT_FIT))
                .executePrepared(claimed, running, mock(SandboxSpec.class));
        UUID queued = queuedAndDue(withoutAssignments(runningJobOwnedBy("live-owner", Instant.now(), 0)));
        var tooSmall = mock(SandboxManager.class);
        when(tooSmall.neverFits(any())).thenReturn(true);
        executorOwnedBy("live-owner", tooSmall).endReviewsWithoutRoom(Instant.now());

        // A tick settles the 100 earliest ended jobs, and earlier tests leave theirs unsettled.
        jdbc.update(
                "UPDATE review_job SET completed_at = completed_at - interval '100 years' WHERE id IN (?, ?)",
                claimed,
                queued);
        var completion = new ReviewJobCompletionService(
                jobRepository,
                mock(ReviewWriteGuard.class),
                assignments,
                members,
                reviews,
                mock(ReviewJobLifecycleService.class),
                transactionTemplate,
                Clock.systemUTC());
        completion.tick();

        for (UUID jobId : List.of(claimed, queued)) {
            UUID reviewId = jobRepository.findById(jobId).orElseThrow().getReviewId();
            assertThat(jdbc.queryForMap("SELECT status, stop_reason FROM review WHERE id = ?", reviewId))
                    .containsEntry("status", "CANCELLED")
                    .containsEntry("stop_reason", "NO_SANDBOX_ROOM");
        }
    }

    /** The job reviews no practice, so settling it touches the review alone; the fixture creates no assignment rows. */
    private UUID withoutAssignments(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setInput(new ReviewJobInput(job.getInput().itemId(), List.of()));
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
        return jobId;
    }

    /** The job waits in the queue and is due: nothing holds it back but room. */
    private UUID queuedAndDue(UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(ReviewJobStatus.QUEUED);
            job.setWorkerId(null);
            job.setAvailableAt(Instant.now().minus(Duration.ofMinutes(1)));
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
        return jobId;
    }

    private ReviewJobRecovery.Outcome recoverAfterDrain(UUID jobId, int attempt) {
        return transactionTemplate.execute(
                status -> recovery.recover(jobId, "live-owner", attempt, ReviewJobCancellationReason.DRAIN_GRACEFUL));
    }

    /** The next claim of the requeued job by the worker. */
    private void claimAgain(UUID jobId, String workerId) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setStatus(ReviewJobStatus.RUNNING);
            job.setWorkerId(workerId);
            job.setStartedAt(Instant.now());
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
    }

    /** The attempts of the job that drains stopped and that found no sandbox room. */
    private void withCounts(UUID jobId, int drains, int roomRefusals) {
        transactionTemplate.executeWithoutResult(status -> {
            ReviewJob job = jobRepository.findById(jobId).orElseThrow();
            job.setDrainCount(drains);
            job.setRoomRefusals(roomRefusals);
            jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        });
    }

    private static SandboxManager refusedSandbox(SandboxCapacityException.Reason reason) {
        var sandbox = mock(SandboxManager.class);
        when(sandbox.execute(any())).thenThrow(new SandboxCapacityException("no room", reason));
        return sandbox;
    }

    private double sandboxStartFailures(SandboxCapacityException.Reason reason) {
        var counter = meterRegistry
                .find("sandbox.start.failures")
                .tag("kind", "review")
                .tag("reason", reason.tag())
                .counter();
        return counter == null ? 0 : counter.count();
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
        return executorOwnedBy(workerId, sandbox);
    }

    private ReviewJobExecutor executorOwnedBy(String workerId, SandboxManager sandbox) {
        var timedOut = new SandboxResult(124, Map.of(), "stopped", true, Duration.ofMinutes(15));
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

    @ParameterizedTest(name = "admission committed: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("recovery waiting on the admission's row lock decides on what the admission left behind")
    void shouldDecideOnTheCommittedAdmissionWhenRecoveryWaitsForItsRowLock(boolean committed) throws Exception {
        UUID jobId = runningJobOwnedBy("dead-replica-7", Instant.now().minus(Duration.ofMinutes(5)), 0);
        expireLeasesOf("dead-replica-7");

        whileRecoveryWaits(jobId, committed, job -> {
            job.setAdmissionDigest("admitted-digest");
            job.setAdmissionAttempt(job.getAttempt());
        });

        ReviewJob after = jobRepository.findById(jobId).orElseThrow();
        if (committed) {
            // Admitted without retained inputs: handed on as a composition-only attempt.
            assertThat(after.getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
            assertThat(after.getAttempt()).isEqualTo(1);
            assertThat(ResultAdmissionService.isContinuation(after)).isTrue();
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
        expireLeasesOf("dead-replica-8");

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
                    .set("manifest", objectMapper.createObjectNode().put("contractVersion", "3.0.0")));
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

    private void expireLeasesOf(String workerId) {
        // The lease check judges each lease on the database clock.
        jdbc.update(
                "UPDATE review_job SET leased_until = now() - make_interval(secs => ?) WHERE worker_id = ?",
                AgentProperties.WORKER_LEASE_TTL.plusMinutes(1).toSeconds(),
                workerId);
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
