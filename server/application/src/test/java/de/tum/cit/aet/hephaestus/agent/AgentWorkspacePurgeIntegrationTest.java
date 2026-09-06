package de.tum.cit.aet.hephaestus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelWorkspaceGrant;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelWorkspaceGrantRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.job.DeliveryStatus;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageJobType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.PricingState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

class AgentWorkspacePurgeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkspaceLifecycleService lifecycleService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private LlmConnectionRepository instanceConnectionRepository;

    @Autowired
    private LlmModelRepository instanceModelRepository;

    @Autowired
    private LlmModelWorkspaceGrantRepository grantRepository;

    @Autowired
    private WorkspaceLlmConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceLlmModelRepository modelRepository;

    @Autowired
    private WorkspaceAgentBindingRepository bindingRepository;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private LlmUsageEventRepository usageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private LlmModel sharedModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        LlmConnection connection = instanceConnectionRepository.save(LlmCatalogTestFixtures.connection("purge-shared"));
        sharedModel =
                instanceModelRepository.save(LlmCatalogTestFixtures.model(connection, "purge-shared", "shared-model"));
    }

    @Test
    void shouldEraseWorkspaceAgentContentAndCredentialsButRetainAccounting() {
        Workspace purgedWorkspace = workspaceRepository.save(TestEntities.activeWorkspace("agent-purge"));
        Workspace otherWorkspace = workspaceRepository.save(TestEntities.activeWorkspace("agent-keep"));
        AgentData purgedData = seedAgentData(purgedWorkspace, "purge");
        AgentData otherData = seedAgentData(otherWorkspace, "keep");

        lifecycleService.purgeWorkspace(purgedWorkspace.getWorkspaceSlug());

        assertThat(jobRepository.findListRows(purgedWorkspace.getId(), null, Pageable.unpaged()))
                .isEmpty();
        assertThat(bindingRepository.findByWorkspaceId(purgedWorkspace.getId())).isEmpty();
        assertThat(modelRepository.findByWorkspaceId(purgedWorkspace.getId())).isEmpty();
        assertThat(connectionRepository.findByWorkspaceId(purgedWorkspace.getId()))
                .isEmpty();
        assertThat(grantRepository.existsByIdModelIdAndIdWorkspaceId(sharedModel.getId(), purgedWorkspace.getId()))
                .isFalse();

        assertThat(jobRepository.findById(otherData.jobId())).isPresent();
        assertThat(bindingRepository.findByWorkspaceId(otherWorkspace.getId())).hasSize(1);
        assertThat(modelRepository.findByWorkspaceId(otherWorkspace.getId())).hasSize(1);
        assertThat(connectionRepository.findByWorkspaceId(otherWorkspace.getId()))
                .hasSize(1);
        assertThat(grantRepository.existsByIdModelIdAndIdWorkspaceId(sharedModel.getId(), otherWorkspace.getId()))
                .isTrue();

        assertThat(usageRepository.findById(purgedData.usageEventId())).isPresent();
        assertThat(usageRepository.findById(otherData.usageEventId())).isPresent();
        assertThat(instanceModelRepository.findById(sharedModel.getId())).isPresent();
    }

    @Test
    void shouldExplainHowToResolvePendingFeedbackDeliveryBeforePurge() {
        Workspace workspace = workspaceRepository.save(TestEntities.activeWorkspace("pending-delivery"));
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setDeliveryStatus(DeliveryStatus.PENDING);
        job.setConfigSnapshot(objectMapper.createObjectNode());
        jobRepository.save(job);

        assertThatThrownBy(() -> lifecycleService.purgeWorkspace(workspace.getWorkspaceSlug()))
                .isInstanceOf(WorkspacePurgeBlockedException.class)
                .hasMessage(
                        "Workspace deletion is blocked while AI runs are queued, running, or awaiting feedback delivery. "
                                + "Cancel queued or running runs, and wait for pending feedback delivery to finish, then try again.");
    }

    private AgentData seedAgentData(Workspace workspace, String suffix) {
        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(workspace);
        connection.setSlug("connection-" + suffix);
        connection.setDisplayName("Connection " + suffix);
        connection.setBaseUrl(LlmCatalogTestFixtures.BASE_URL);
        connection.setApiProtocol(LlmCatalogTestFixtures.OPENAI_COMPLETIONS);
        connection.setApiKey("secret-" + suffix);
        connection.setEnabled(true);
        connection = connectionRepository.save(connection);

        WorkspaceLlmModel model = new WorkspaceLlmModel();
        model.setWorkspace(workspace);
        model.setConnection(connection);
        model.setSlug("model-" + suffix);
        model.setDisplayName("Model " + suffix);
        model.setUpstreamModelId("upstream-" + suffix);
        model.setEnabled(true);
        model = modelRepository.save(model);

        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setWorkspaceModel(model);
        bindingRepository.save(binding);

        LlmModelWorkspaceGrant grant = new LlmModelWorkspaceGrant(sharedModel.getId(), workspace.getId());
        grant.setGrantedAt(Instant.now());
        grantRepository.save(grant);

        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setDeliveryStatus(DeliveryStatus.DELIVERED);
        job.setMetadata(objectMapper.createObjectNode().put("private", "content-" + suffix));
        job.setConfigSnapshot(objectMapper.createObjectNode().put("baseUrl", connection.getBaseUrl()));
        job.setContainerLogs("private logs " + suffix);
        job = jobRepository.save(job);

        LlmUsageEvent usage = new LlmUsageEvent();
        usage.setId(UUID.randomUUID());
        usage.setWorkspace(workspace);
        usage.setJobType(LlmUsageJobType.PULL_REQUEST_REVIEW);
        usage.setSourceType(LlmUsageSourceType.AGENT_JOB);
        usage.setSourceId(job.getId());
        usage.setOccurredAt(Instant.now());
        usage.setPricingState(PricingState.UNPRICED);
        usage.setFundingSource(FundingSource.WORKSPACE);
        usageRepository.save(usage);

        return new AgentData(job.getId(), usage.getId());
    }

    private record AgentData(UUID jobId, UUID usageEventId) {}
}
