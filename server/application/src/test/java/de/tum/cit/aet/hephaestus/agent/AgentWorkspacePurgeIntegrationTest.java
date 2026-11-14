package de.tum.cit.aet.hephaestus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
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
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEvent;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AgentWorkspacePurgeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

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
    private WorkspaceModelAssignmentRepository bindingRepository;

    @Autowired
    private ReviewJobRepository jobRepository;

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

        assertThat(jobRepository.findByIdAndWorkspaceId(purgedData.jobId(), purgedWorkspace.getId()))
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
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setProcessingStatus(ProcessingStatus.PENDING);
        job.setConfigSnapshot(objectMapper.createObjectNode());
        jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));

        assertThatThrownBy(() -> lifecycleService.purgeWorkspace(workspace.getWorkspaceSlug()))
                .isInstanceOf(WorkspacePurgeBlockedException.class)
                .hasMessage(
                        "You cannot delete this workspace while AI runs are queued, running, or waiting for feedback"
                                + " delivery. Cancel queued and running runs, wait for pending feedback delivery to"
                                + " finish, then try again.");
    }

    private AgentData seedAgentData(Workspace workspace, String suffix) {
        WorkspaceLlmConnection connection = new WorkspaceLlmConnection();
        connection.setWorkspace(workspace);
        connection.setSlug("connection-" + suffix);
        connection.setDisplayName("Connection " + suffix);
        connection.setBaseUrl(LlmCatalogTestFixtures.BASE_URL);
        connection.setApiProtocol(LlmApiProtocol.OPENAI_COMPLETIONS);
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

        WorkspaceModelAssignment binding = new WorkspaceModelAssignment();
        binding.setWorkspace(workspace);
        binding.setPurpose(ModelPurpose.PRACTICE_REVIEW);
        binding.setWorkspaceModel(model);
        bindingRepository.save(binding);

        LlmModelWorkspaceGrant grant = new LlmModelWorkspaceGrant(sharedModel.getId(), workspace.getId());
        grant.setGrantedAt(Instant.now());
        grantRepository.save(grant);

        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setProcessingStatus(ProcessingStatus.DONE);
        job.setCapture(objectMapper.createObjectNode().put("private", "content-" + suffix));
        job.setConfigSnapshot(objectMapper.createObjectNode().put("baseUrl", connection.getBaseUrl()));

        job = jobRepository.save(RuntimeJobTestData.persistParent(jdbc, job));

        LlmUsageEvent usage = new LlmUsageEvent();
        usage.setId(UUID.randomUUID());
        usage.setWorkspaceId(workspace.getId());
        usage.setJobType("PULL_REQUEST_REVIEW");
        usage.setSourceType(UsageSourceType.REVIEW_JOB);
        usage.setSourceId(job.getId());
        usage.setOccurredAt(Instant.now());
        usage.setStartedAt(usage.getOccurredAt());
        usage.setPricingState(UsagePricingState.UNPRICED);
        usage.setFundingSource(UsageFundingSource.WORKSPACE);
        usageRepository.save(usage);

        return new AgentData(job.getId(), usage.getId());
    }

    private record AgentData(UUID jobId, UUID usageEventId) {}
}
