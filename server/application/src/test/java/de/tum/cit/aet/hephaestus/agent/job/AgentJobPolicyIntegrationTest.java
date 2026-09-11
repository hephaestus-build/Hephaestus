package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.DataHandlingFacts;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataOperator;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmDataRetention;
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
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxManager;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.WorkerJwtIssuer;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentJobPolicyIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ObservationAdmissionService observationAdmission;

    @Autowired
    private FeedbackDispatchRepository feedbackDispatches;

    @Autowired
    private AgentJobRepository jobs;

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
    private AgentJobExecutor executor;

    @BeforeEach
    void setUp() {
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("tier-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(anyLong(), any(), any());
        when(policy.binding(anyLong(), any(), any())).thenAnswer(invocation -> {
            var metadata = invocation.getArgument(2, ObjectNode.class);
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                    invocation.getArgument(0),
                    AgentPurpose.PRACTICE_REVIEW,
                    DataHandlingTier.valueOf(metadata.path("dataHandlingTier").asString()));
        });
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        // Hold dispatched sandbox work outside this test: admission, locks and RUNNING rows are real.
        executor = new AgentJobExecutor(
                mock(de.tum.cit.aet.hephaestus.agent.job.ExecutionArchiveService.class),
                properties,
                jobs,
                policy,
                mock(JobTypeHandlerRegistry.class),
                mock(PracticePiAdapter.class),
                mock(WorkerJwtIssuer.class),
                mock(SandboxManager.class),
                mock(AsyncTaskExecutor.class),
                transactions,
                mapper,
                metrics,
                new PracticeReviewRefusalMetrics(metrics),
                new AgentJobTelemetry(metrics, io.micrometer.tracing.Tracer.NOOP),
                mock(LlmUsageRecorder.class),
                budgets,
                admission,
                Optional.empty(),
                Optional.empty(),
                observationAdmission);
    }

    @Test
    void shouldAllowIndependentTierSlotsButSerializeClaimsWithinEachSlot() throws Exception {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var kept = binding(DataHandlingTier.PROVIDER_KEPT);
        var first = queued(inHouse);
        var second = queued(inHouse);
        var start = new CyclicBarrier(2);
        when(policy.permitsReview(anyLong(), any(), any())).thenAnswer(invocation -> {
            start.await(30, TimeUnit.SECONDS);
            return true;
        });
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstClaim = threads.submit(() -> executor.processJob(first.getId()));
            var secondClaim = threads.submit(() -> executor.processJob(second.getId()));
            assertThat(List.of(firstClaim.get(30, TimeUnit.SECONDS), secondClaim.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jobs.findAllById(List.of(first.getId(), second.getId())))
                .extracting(AgentJob::getStatus)
                .containsExactlyInAnyOrder(AgentJobStatus.RUNNING, AgentJobStatus.QUEUED);

        doReturn(true).when(policy).permitsReview(anyLong(), any(), any());
        var keptJob = queued(kept);
        assertThat(executor.processJob(keptJob.getId())).isTrue();
        assertThat(jobs.findById(keptJob.getId()))
                .get()
                .extracting(AgentJob::getStatus)
                .isEqualTo(AgentJobStatus.RUNNING);
    }

    @Test
    void shouldKeepRunningOccupancyWhenTheSlotsModelIsReassigned() {
        var binding = binding(DataHandlingTier.IN_HOUSE);
        var running = queued(binding);
        assertThat(executor.processJob(running.getId())).isTrue();

        var replacement = model(DataHandlingTier.IN_HOUSE);
        binding.setInstanceModel(replacement);
        binding = bindings.saveAndFlush(binding);
        var queued = queued(binding);
        assertThat(executor.processJob(queued.getId())).isFalse();
        assertThat(jobs.findById(queued.getId()))
                .get()
                .extracting(AgentJob::getStatus)
                .isEqualTo(AgentJobStatus.QUEUED);
    }

    @Test
    void shouldCountHistoricalSnapshotsAgainstOnlyTheUndeclaredSlot() {
        var binding = binding(DataHandlingTier.UNDECLARED);
        var historical = queued(binding);
        var snapshot = (ObjectNode) historical.getConfigSnapshot();
        snapshot.remove("dataHandlingTier");
        historical.setConfigSnapshot(snapshot);
        historical.setStatus(AgentJobStatus.RUNNING);
        jobs.saveAndFlush(historical);

        assertThat(executor.processJob(queued(binding).getId())).isFalse();
        assertThat(executor.processJob(
                        queued(binding(DataHandlingTier.IN_HOUSE)).getId()))
                .isTrue();
    }

    @Test
    void shouldRefuseAQueuedJobWhoseSnapshotTierDiffersFromTheBindingResolvedNow() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var job = queued(inHouse);
        var routedElsewhere = binding(DataHandlingTier.PROVIDER_KEPT);
        doReturn(Optional.of(routedElsewhere)).when(policy).binding(anyLong(), any(), any());

        assertThat(executor.processJob(job.getId())).isFalse();
        assertThat(jobs.findById(job.getId()))
                .get()
                .extracting(AgentJob::getStatus, AgentJob::getCancellationReason)
                .containsExactly(AgentJobStatus.CANCELLED, AgentJobCancellationReason.MODEL_UNAVAILABLE);
    }

    @Test
    void shouldServeAMemberWhoAcceptsAnyDeclaredTierFromAnInHouseBindingWhenItIsTheOnlyOne() {
        var inHouse = binding(DataHandlingTier.IN_HOUSE);
        var preferences = mock(MemberAiPreferences.class);
        when(preferences.forDeveloper(workspace.getId(), 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.ANY_DECLARED));
        var routing = new MemberAiRoutingAdapter(bindings, preferences, resolver, workspaces);

        assertThat(routing.binding(workspace.getId(), AgentPurpose.PRACTICE_REVIEW, 20L))
                .get()
                .extracting(WorkspaceAgentBinding::getId)
                .isEqualTo(inHouse.getId());
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentJobType.class,
            names = {"CONVERSATION_REVIEW", "DOCUMENT_REVIEW"})
    void shouldKeepTheConsentRefusalAfterDirectAdapterDeliveryRetry(AgentJobType type) {
        var job = completedDirectJob(type, DeliveryStatus.FAILED);
        var handler = mock(JobTypeHandler.class);
        doThrow(new ObservationsRefusedException("member_ai_declined", "The developer chose No AI."))
                .when(handler)
                .deliver(any());

        assertThatThrownBy(() -> lifecycle(type, handler).retryDelivery(workspace.getId(), job.getId()))
                .isInstanceOf(AgentJobStateConflictException.class);

        assertStoredRefusal(job, DeliveryStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentJobType.class,
            names = {"CONVERSATION_REVIEW", "DOCUMENT_REVIEW"})
    void shouldKeepTheConsentRefusalAfterDirectAdapterDeliveryRecovery(AgentJobType type) {
        var job = completedDirectJob(type, DeliveryStatus.PENDING);
        var handler = mock(JobTypeHandler.class);
        when(handler.findExistingDelivery(job)).thenReturn(ExistingDeliveryLookup.absent());
        doThrow(new ObservationsRefusedException("member_ai_declined", "The developer chose No AI."))
                .when(handler)
                .deliver(job);

        assertThat(lifecycle(type, handler).recoverStuckDelivery(job, (short) 1))
                .isFalse();

        assertStoredRefusal(job, DeliveryStatus.PENDING);
    }

    private AgentJob completedDirectJob(AgentJobType type, DeliveryStatus deliveryStatus) {
        var job = queued(binding(DataHandlingTier.IN_HOUSE));
        job.setJobType(type);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setDeliveryStatus(deliveryStatus);
        return jobs.saveAndFlush(job);
    }

    private AgentJobLifecycleService lifecycle(AgentJobType type, JobTypeHandler handler) {
        var handlers = mock(JobTypeHandlerRegistry.class);
        when(handlers.getHandler(type)).thenReturn(handler);
        return new AgentJobLifecycleService(
                jobs,
                handlers,
                transactions,
                mock(SandboxManager.class),
                Optional.empty(),
                mock(LlmUsageRecorder.class),
                mapper,
                feedbackDispatches,
                new AgentJobTelemetry(new SimpleMeterRegistry(), io.micrometer.tracing.Tracer.NOOP),
                observationAdmission);
    }

    private void assertStoredRefusal(AgentJob job, DeliveryStatus deliveryStatus) {
        var stored = jobs.findByIdAndWorkspaceId(job.getId(), workspace.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(stored.getDeliveryStatus()).isEqualTo(deliveryStatus);
        var metadata = Objects.requireNonNull(stored.getMetadata());
        assertThat(metadata.path("dataHandlingTier").asString()).isEqualTo("IN_HOUSE");
        assertThat(metadata.path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reasonCode")
                        .asString())
                .isEqualTo("member_ai_declined");
        assertThat(metadata.path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reason")
                        .asString())
                .isEqualTo("The developer chose No AI.");
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
                    case IN_HOUSE ->
                        DataHandlingFacts.of(LlmDataOperator.OWN_ORGANISATION, LlmDataRetention.NONE, null);
                    case PROVIDER_NOT_KEPT ->
                        DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.NONE, null);
                    case PROVIDER_KEPT ->
                        DataHandlingFacts.of(LlmDataOperator.PROVIDER, LlmDataRetention.FOR_SAFETY_CHECKS, null);
                    case UNDECLARED -> new DataHandlingFacts();
                });
        model = models.save(model);
        var price = new LlmModelPrice();
        price.setModel(model);
        price.setPricingMode(PricingMode.NO_CHARGE);
        price.setEffectiveFrom(Instant.now());
        prices.saveAndFlush(price);
        return model;
    }

    private AgentJob queued(WorkspaceAgentBinding binding) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var currentBinding = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspace.getId(), AgentPurpose.PRACTICE_REVIEW, binding.getDataHandlingTier())
                    .orElseThrow();
            var job = new AgentJob();
            job.setWorkspace(workspace);
            job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            job.setMetadata(mapper.createObjectNode()
                    .put("dataHandlingTier", binding.getDataHandlingTier().name()));
            job.setConfigSnapshot(ConfigSnapshot.from(currentBinding, resolver)
                    .withPriceSnapshot(admission.admit(currentBinding).price())
                    .toJson(mapper));
            return jobs.saveAndFlush(job);
        }));
    }
}
