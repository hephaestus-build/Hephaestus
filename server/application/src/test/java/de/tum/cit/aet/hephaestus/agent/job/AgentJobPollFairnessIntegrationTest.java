package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Head-of-line starvation fix for the poll candidate query. A plain {@code WHERE status='QUEUED' ORDER
 * BY created_at LIMIT n} has no awareness of per-bucket concurrency caps: if the oldest {@code n}
 * QUEUED rows all belong to a {@code (workspace, purpose)} already saturated on RUNNING jobs, a
 * younger, immediately-runnable job in a different bucket never enters the candidate batch at all.
 */
@DisplayName("Poll candidate fairness")
class AgentJobPollFairnessIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private WorkspaceAgentBindingRepository bindingRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private LlmConnectionRepository llmConnectionRepository;

    @Autowired
    private LlmModelRepository llmModelRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private LlmModel instanceModel;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        LlmConnection connection = llmConnectionRepository.save(LlmCatalogTestFixtures.connection("poll-fairness"));
        instanceModel = llmModelRepository.save(
                LlmCatalogTestFixtures.model(connection, "poll-fairness-model", "gpt-poll-fairness"));
    }

    @Test
    @DisplayName(
            "a saturated (workspace, purpose)'s older QUEUED backlog does not starve a younger, runnable job from another workspace")
    void youngerRunnableJobFromAnotherWorkspaceIsNotStarved() {
        Workspace cappedWs = activeWorkspace("capped-ws");
        Workspace openWs = activeWorkspace("open-ws");
        binding(cappedWs, 1);
        binding(openWs, 3);

        runningJob(cappedWs);

        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 5; i++) {
            queuedJob(cappedWs, base.plusSeconds(i));
        }

        // A younger job on the OPEN workspace — created after all 5 capped candidates, so a naive
        // oldest-first LIMIT 5 query would never even fetch it.
        UUID youngerRunnableJobId = queuedJob(openWs, base.plusSeconds(100));

        List<UUID> candidates = jobRepository.findQueuedIdsOldestFirst(5);

        assertThat(candidates)
                .as("the open workspace's runnable job must be a poll candidate even though 5 older, unclaimable "
                        + "jobs exist ahead of it")
                .contains(youngerRunnableJobId);
    }

    @Test
    @DisplayName("a (workspace, purpose) at its concurrency cap contributes no candidates at all")
    void cappedWorkspaceContributesNoCandidates() {
        Workspace cappedWs = activeWorkspace("fully-capped-ws");
        binding(cappedWs, 1);
        runningJob(cappedWs);
        UUID queuedOnCapped = queuedJob(cappedWs, Instant.now());

        List<UUID> candidates = jobRepository.findQueuedIdsOldestFirst(10);

        assertThat(candidates).doesNotContain(queuedOnCapped);
    }

    @Test
    @DisplayName("a job whose (workspace, purpose) binding is gone is treated as uncapped and stays a candidate")
    void jobWithNoBindingIsAlwaysACandidate() {
        Workspace ws = activeWorkspace("no-binding-ws");
        // No binding row for this workspace/purpose — the correlated cap lookup returns NULL, which
        // COALESCEs to "uncapped", so the job is still fetched (the claim's admission re-check is the
        // authoritative gate that will reject an unbound job — it must not silently starve siblings here).
        UUID jobId = queuedJob(ws, Instant.now());

        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).contains(jobId);
    }

    /**
     * A workspace may bind one slot per AI tier for a purpose; a job counts against the slot of the tier its
     * snapshot froze, the partition the claim enforces. Whether the other slot is enabled does not matter here.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSelectAQueuedReviewWhenItsWorkspaceBindsOneSlotPerTier(boolean otherSlotEnabled) {
        Workspace ws = activeWorkspace("tiered-ws");
        binding(ws, DataHandlingTier.IN_HOUSE, 1, true);
        binding(ws, DataHandlingTier.UNDECLARED, 1, otherSlotEnabled);
        UUID jobId = queuedJob(ws, Instant.now(), DataHandlingTier.IN_HOUSE);

        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).contains(jobId);
    }

    @Test
    void shouldNotLetASaturatedTierHideAnotherTierOrAnotherWorkspace() {
        Workspace tiered = activeWorkspace("saturated-tier-ws");
        Workspace other = activeWorkspace("other-ws");
        binding(tiered, DataHandlingTier.IN_HOUSE, 1, true);
        binding(tiered, DataHandlingTier.UNDECLARED, 1, true);
        binding(other, 1);
        runningJob(tiered, DataHandlingTier.IN_HOUSE);

        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        List<UUID> saturated = List.of(
                queuedJob(tiered, base, DataHandlingTier.IN_HOUSE),
                queuedJob(tiered, base.plusSeconds(1), DataHandlingTier.IN_HOUSE),
                queuedJob(tiered, base.plusSeconds(2), DataHandlingTier.IN_HOUSE));
        UUID otherTier = queuedJob(tiered, base.plusSeconds(10), DataHandlingTier.UNDECLARED);
        UUID otherWorkspace = queuedJob(other, base.plusSeconds(20));

        List<UUID> candidates = jobRepository.findQueuedIdsOldestFirst(2);

        assertThat(candidates).contains(otherTier, otherWorkspace).doesNotContainAnyElementsOf(saturated);
    }

    /** A job stored before snapshots named a tier counts against the UNDECLARED slot, as the claim counts it. */
    @Test
    void shouldCountAHistoricalRunningJobAgainstOnlyTheUndeclaredSlot() {
        Workspace ws = activeWorkspace("historical-ws");
        binding(ws, DataHandlingTier.IN_HOUSE, 1, true);
        binding(ws, DataHandlingTier.UNDECLARED, 1, true);
        runningJob(ws, null);
        UUID undeclared = queuedJob(ws, Instant.now(), null);
        UUID inHouse = queuedJob(ws, Instant.now(), DataHandlingTier.IN_HOUSE);

        List<UUID> candidates = jobRepository.findQueuedIdsOldestFirst(10);

        assertThat(candidates).contains(inHouse).doesNotContain(undeclared);
    }

    /** No slot is bound for the job's tier: it stays a candidate, and the claim refuses it. */
    @Test
    void shouldKeepAJobWhoseTierHasNoBindingACandidateBesideASaturatedSlot() {
        Workspace ws = activeWorkspace("unbound-tier-ws");
        binding(ws, 1);
        runningJob(ws);
        UUID unbound = queuedJob(ws, Instant.now(), DataHandlingTier.IN_HOUSE);

        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).contains(unbound);
    }

    @Test
    void purgedWorkspaceJobsCannotBePolledOrClaimed() {
        Workspace workspace = activeWorkspace("purged-ws");
        UUID jobId = queuedJob(workspace, Instant.now());
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(jobRepository.findQueuedIdsOldestFirst(10)).doesNotContain(jobId);
        Optional<AgentJob> claimed = transactionTemplate.execute(
                status -> jobRepository.findByIdQueuedForUpdateSkipLocked(jobId, Instant.now()));
        assertThat(claimed).isEmpty();
    }

    private Workspace activeWorkspace(String slug) {
        return workspaceRepository.save(TestEntities.activeWorkspace(slug));
    }

    private void binding(Workspace workspace, int maxConcurrentJobs) {
        binding(workspace, DataHandlingTier.UNDECLARED, maxConcurrentJobs, true);
    }

    private void binding(Workspace workspace, DataHandlingTier tier, int maxConcurrentJobs, boolean enabled) {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(tier);
        binding.setInstanceModel(instanceModel);
        binding.setEnabled(enabled);
        binding.setMaxConcurrentJobs(maxConcurrentJobs);
        bindingRepository.saveAndFlush(binding);
    }

    private UUID runningJob(Workspace workspace) {
        return runningJob(workspace, null);
    }

    /** A job running on the slot of {@code tier}; null leaves the tier out of its snapshot, as older jobs did. */
    private UUID runningJob(Workspace workspace, @Nullable DataHandlingTier tier) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setWorkerId("some-worker");
        job.setConfigSnapshot(snapshot(tier));
        return jobRepository.saveAndFlush(job).getId();
    }

    private UUID queuedJob(Workspace workspace, Instant createdAt) {
        return queuedJob(workspace, createdAt, null);
    }

    private UUID queuedJob(Workspace workspace, Instant createdAt, @Nullable DataHandlingTier tier) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.QUEUED);
        job.setConfigSnapshot(snapshot(tier));
        // @PrePersist only stamps createdAt when it is still null, so setting it up front here (before
        // the first save/flush, i.e. before @PrePersist fires) lets this fixture control ordering exactly
        // — created_at is `updatable=false`, so a second UPDATE after the initial INSERT would silently
        // no-op instead of changing it.
        job.setCreatedAt(createdAt);
        UUID jobId = jobRepository.saveAndFlush(job).getId();
        // The candidate query compares available_at with the database clock; an older createdAt keeps its order.
        jdbc.update(
                "UPDATE agent_job SET available_at = LEAST(created_at, now() - interval '1 second') WHERE id = ?",
                jobId);
        return jobId;
    }

    private ObjectNode snapshot(@Nullable DataHandlingTier tier) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        if (tier != null) {
            snapshot.put("dataHandlingTier", tier.name());
        }
        return snapshot;
    }
}
