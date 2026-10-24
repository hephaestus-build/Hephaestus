package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewJobPolicyIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private ReviewAttemptUsageRepository attemptUsageRows;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelPriceRepository prices;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private LlmAdmissionService admission;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentProperties properties;

    private final ReviewMemberAiPolicy policy = mock(ReviewMemberAiPolicy.class);
    private Workspace workspace;
    private ReviewJobExecutor executor;
    private final ReviewMethodVerifier methods = mock(ReviewMethodVerifier.class);

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("tier-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(any());
        when(policy.binding(any())).thenAnswer(invocation -> {
            ReviewJob selected = invocation.getArgument(0);
            var snapshot = ConfigSnapshot.fromJson(selected.getConfigSnapshot(), mapper);
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                    selected.getWorkspaceId(),
                    AgentPurpose.PRACTICE_REVIEW,
                    Objects.requireNonNull(snapshot.model().dataHandlingTier()));
        });
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        var assignmentSnapshots = mock(ReviewJobAssignments.class);
        when(assignmentSnapshots.load(any())).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        // Hold sandbox work outside this test: admission, locks and RUNNING rows are real.
        executor = new ReviewJobExecutor(
                properties,
                jobs,
                mock(DefaultReviewExecutionHandler.class),
                mock(PracticePiAdapter.class),
                mock(WorkerJwtIssuer.class),
                mock(SandboxManager.class),
                SandboxTestLimits.PROPERTIES,
                mock(AsyncTaskExecutor.class),
                transactions,
                mapper,
                metrics,
                new ReviewJobTelemetry(metrics, Tracer.NOOP),
                new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class)),
                mock(PracticeScriptRunRecorder.class),
                admission,
                Optional.empty(),
                Optional.empty(),
                new ReviewJobClaimService(
                        jobs,
                        policy,
                        methods,
                        assignmentSnapshots,
                        transactions,
                        mapper,
                        metrics,
                        budgets,
                        admission,
                        new ReviewJobTelemetry(metrics, Tracer.NOOP),
                        Optional.empty()),
                new ReviewJobRecovery(
                        jobs,
                        properties,
                        mapper,
                        new ReviewAttemptUsageService(jobs, attemptUsageRows, mock(LlmUsageRecorder.class)),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void shouldAllowIndependentTierSlotsButSerializeClaimsWithinEachSlot() throws Exception {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var cloud = binding(DataHandlingTier.CLOUD);
        var first = queued(inHouse);
        var second = queued(inHouse);
        var start = new CyclicBarrier(2);
        when(policy.permitsReview(any())).thenAnswer(invocation -> {
            start.await(30, TimeUnit.SECONDS);
            return true;
        });
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClaim = threads.submit(() -> executor.processJob(first.getId()));
            var secondClaim = threads.submit(() -> executor.processJob(second.getId()));
            assertThat(List.of(firstClaim.get(30, TimeUnit.SECONDS), secondClaim.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            ReviewJobExecutor.StartOutcome.STARTED, ReviewJobExecutor.StartOutcome.SKIPPED);
        }
        assertThat(jobs.findAllById(List.of(first.getId(), second.getId())))
                .extracting(ReviewJob::getStatus)
                .containsExactlyInAnyOrder(ReviewJobStatus.RUNNING, ReviewJobStatus.QUEUED);

        doReturn(true).when(policy).permitsReview(any());
        var cloudJob = queued(cloud);
        assertThat(executor.processJob(cloudJob.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
        assertThat(jobs.findById(cloudJob.getId()))
                .get()
                .extracting(ReviewJob::getStatus)
                .isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    void shouldKeepRunningOccupancyWhenTheSlotsModelIsReassigned() {
        var binding = binding(DataHandlingTier.IN_HOUSE);
        var running = queued(binding);
        assertThat(executor.processJob(running.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);

        var replacement = model(DataHandlingTier.IN_HOUSE);
        binding.setInstanceModel(replacement);
        binding = bindings.saveAndFlush(binding);
        var queued = queued(binding);
        assertThat(executor.processJob(queued.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(queued.getId()))
                .get()
                .extracting(ReviewJob::getStatus)
                .isEqualTo(ReviewJobStatus.QUEUED);
    }

    @Test
    void shouldCountAnUndeclaredModelAgainstOnlyTheUndeclaredSlot() {
        var binding = binding(DataHandlingTier.UNDECLARED);
        var historical = queued(binding);
        var snapshot = (ObjectNode) historical.getConfigSnapshot();
        ((ObjectNode) snapshot.get("model")).remove("dataHandlingTier");
        historical.setConfigSnapshot(snapshot);
        historical.setStatus(ReviewJobStatus.RUNNING);
        jobs.saveAndFlush(historical);

        assertThat(executor.processJob(queued(binding).getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(executor.processJob(
                        queued(binding(DataHandlingTier.IN_HOUSE)).getId()))
                .isEqualTo(ReviewJobExecutor.StartOutcome.STARTED);
    }

    @Test
    void shouldRefuseAQueuedJobWhoseSnapshotTierDiffersFromTheBindingResolvedNow() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(inHouse);
        var routedElsewhere = binding(DataHandlingTier.CLOUD);
        doReturn(Optional.of(routedElsewhere)).when(policy).binding(any(ReviewJob.class));

        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldCancelAQueuedJobWhenItsFrozenModelBecomesUnavailable() {
        var slot = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(slot);
        var model = Objects.requireNonNull(slot.getInstanceModel());
        model.setEnabled(false);
        models.saveAndFlush(model);
        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.CANCELLED, ReviewJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldKeepTheJobQueuedWhenModelAdmissionFailsUnexpectedly() {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        var failure = new IllegalStateException("Unexpected programming error");
        doThrow(failure).when(methods).require(any(), any(), any());
        assertThat(executor.processJob(job.getId())).isEqualTo(ReviewJobExecutor.StartOutcome.SKIPPED);
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(ReviewJob::getStatus, ReviewJob::getCancellationReason)
                .containsExactly(ReviewJobStatus.QUEUED, null);
    }

    @Test
    void shouldServeAMemberWhoAcceptsTheCloudFromAnInHouseBindingWhenItIsTheOnlyOne() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var preferences = mock(MemberAiPreferences.class);
        when(preferences.forDeveloper(workspace.getId(), 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, AiChoice.CLOUD));
        var routing = new MemberAiRoutingAdapter(
                bindings, preferences, resolver, workspaces, mock(PersonProcessingSuppression.class));

        assertThat(routing.binding(workspace.getId(), AgentPurpose.PRACTICE_REVIEW, 20L))
                .get()
                .extracting(WorkspaceAgentBinding::getId)
                .isEqualTo(inHouse.getId());
    }

    private WorkspaceAgentBinding binding(DataHandlingTier tier) {
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(tier);
        binding.setInstanceModel(model(tier));
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(1);
        return bindings.saveAndFlush(binding);
    }

    private LlmModel model(DataHandlingTier tier) {
        String slug = "tier-" + UUID.randomUUID();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = LlmCatalogTestFixtures.model(connection, slug, slug);
        model.setDataHandling(
                switch (tier) {
                    case IN_HOUSE -> DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, null);
                    case CLOUD -> DataHandlingFacts.of(LlmDataOperator.PROVIDER, null);
                    case UNDECLARED -> new DataHandlingFacts();
                });
        model = models.save(model);
        var price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setNote("No charge for the declared test model.");
        price.setEffectiveFrom(Instant.now());
        prices.saveAndFlush(price);
        return model;
    }

    private ReviewJob queued(WorkspaceAgentBinding binding) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var currentBinding = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspace.getId(), AgentPurpose.PRACTICE_REVIEW, binding.getDataHandlingTier())
                    .orElseThrow();
            var job = new ReviewJob();
            RuntimeJobTestData.initialize(job, workspace.getId());

            job.setConfigSnapshot(ConfigSnapshot.from(currentBinding, resolver)
                    .withPriceSnapshot(admission.admit(currentBinding).price())
                    .toJson(mapper));
            return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        }));
    }
}
