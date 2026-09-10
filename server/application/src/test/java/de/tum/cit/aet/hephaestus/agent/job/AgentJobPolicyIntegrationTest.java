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
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPrice;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelPriceRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmProcessingLocation;
import de.tum.cit.aet.hephaestus.agent.catalog.PricingMode;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
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
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("location-" + UUID.randomUUID()));
        doReturn(true).when(policy).permitsReview(anyLong(), any(), any());
        when(policy.binding(anyLong(), any(), any())).thenAnswer(invocation -> {
            var metadata = invocation.getArgument(2, ObjectNode.class);
            return bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(
                    invocation.getArgument(0),
                    AgentPurpose.PRACTICE_REVIEW,
                    LlmProcessingLocation.valueOf(
                            metadata.path("processingLocation").asString()));
        });
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var metrics = new SimpleMeterRegistry();
        // Hold dispatched sandbox work outside this test: admission, locks and RUNNING rows are real.
        executor = new AgentJobExecutor(
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
                new AgentJobTelemetry(metrics),
                mock(LlmUsageRecorder.class),
                budgets,
                admission,
                Optional.empty(),
                Optional.empty(),
                observationAdmission);
    }

    @Test
    void shouldAllowIndependentLocationsButSerializeClaimsWithinEachLocation() throws Exception {
        var onPremises = binding(LlmProcessingLocation.ON_PREMISES);
        var cloud = binding(LlmProcessingLocation.PRIVATE_CLOUD);
        var first = queued(onPremises);
        var second = queued(onPremises);
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
        var cloudJob = queued(cloud);
        assertThat(executor.processJob(cloudJob.getId())).isTrue();
        assertThat(jobs.findById(cloudJob.getId()))
                .get()
                .extracting(AgentJob::getStatus)
                .isEqualTo(AgentJobStatus.RUNNING);
    }

    @Test
    void shouldKeepRunningOccupancyWhenTheLocationsModelIsReassigned() {
        var binding = binding(LlmProcessingLocation.ON_PREMISES);
        var running = queued(binding);
        assertThat(executor.processJob(running.getId())).isTrue();

        var replacement = model(LlmProcessingLocation.ON_PREMISES);
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
    void shouldCountHistoricalSnapshotsAgainstOnlyTheWorkspaceDefault() {
        var binding = binding(LlmProcessingLocation.UNCLASSIFIED);
        var historical = queued(binding);
        var snapshot = (ObjectNode) historical.getConfigSnapshot();
        snapshot.remove("processingLocation");
        historical.setConfigSnapshot(snapshot);
        historical.setStatus(AgentJobStatus.RUNNING);
        jobs.saveAndFlush(historical);

        assertThat(executor.processJob(queued(binding).getId())).isFalse();
        assertThat(executor.processJob(
                        queued(binding(LlmProcessingLocation.ON_PREMISES)).getId()))
                .isTrue();
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
        var job = queued(binding(LlmProcessingLocation.ON_PREMISES));
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
                new AgentJobTelemetry(new SimpleMeterRegistry()),
                observationAdmission);
    }

    private void assertStoredRefusal(AgentJob job, DeliveryStatus deliveryStatus) {
        var stored = jobs.findByIdAndWorkspaceId(job.getId(), workspace.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(stored.getDeliveryStatus()).isEqualTo(deliveryStatus);
        var metadata = Objects.requireNonNull(stored.getMetadata());
        assertThat(metadata.path("processingLocation").asString()).isEqualTo("ON_PREMISES");
        assertThat(metadata.path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reasonCode")
                        .asString())
                .isEqualTo("member_ai_declined");
        assertThat(metadata.path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reason")
                        .asString())
                .isEqualTo("The developer chose No AI.");
    }

    private WorkspaceAgentBinding binding(LlmProcessingLocation location) {
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setProcessingLocation(location);
        binding.setInstanceModel(model(location));
        binding.setEnabled(true);
        binding.setMaxConcurrentJobs(1);
        return bindings.saveAndFlush(binding);
    }

    private LlmModel model(LlmProcessingLocation location) {
        String slug = "location-" + UUID.randomUUID();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = LlmCatalogTestFixtures.model(connection, slug, slug);
        model.setProcessingLocation(location);
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
            var currentBinding = bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(
                            workspace.getId(), AgentPurpose.PRACTICE_REVIEW, binding.getProcessingLocation())
                    .orElseThrow();
            var job = new AgentJob();
            job.setWorkspace(workspace);
            job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
            job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
            job.setMetadata(mapper.createObjectNode()
                    .put("processingLocation", binding.getProcessingLocation().name()));
            job.setConfigSnapshot(ConfigSnapshot.from(currentBinding, resolver)
                    .withPriceSnapshot(admission.admit(currentBinding).price())
                    .toJson(mapper));
            return jobs.saveAndFlush(job);
        }));
    }
}
