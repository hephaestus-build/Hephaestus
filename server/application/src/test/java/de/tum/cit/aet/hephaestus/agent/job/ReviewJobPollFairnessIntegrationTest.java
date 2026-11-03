package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPool;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnection;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// Saturated oldest buckets must not exclude runnable jobs in other buckets from the candidate batch.
@DisplayName("Poll candidate fairness")
class ReviewJobPollFairnessIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewJobRepository jobRepository;

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

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldPollAnotherEndpointWhenTheCurrentPollExcludesAnUnavailablePool() {
        var blockedWorkspace = activeWorkspace("endpoint-blocked");
        var openWorkspace = activeWorkspace("endpoint-open");
        binding(blockedWorkspace);
        binding(openWorkspace);
        var pools = context.getBean(LlmCallPoolRepository.class);
        var blocked = new LlmCallPool();
        blocked.setSlug("blocked-" + UUID.randomUUID());
        blocked.setDisplayName("Unavailable endpoint");
        blocked.setMaxConcurrentCalls(2);
        blocked.setNoCallBefore(Instant.now().plusSeconds(60));
        blocked = pools.saveAndFlush(blocked);
        jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", blocked.getId(), instanceModel.getId());
        Instant old = Instant.now().minusSeconds(3600);
        UUID first = null;
        for (int i = 0; i < 5; i++) {
            UUID id = queuedJob(blockedWorkspace, old.plusSeconds(i));
            if (first == null) first = id;
            var snapshot = objectMapper.createObjectNode();
            snapshot.putObject("model")
                    .put("connectionScope", "INSTANCE")
                    .put("modelId", instanceModel.getId())
                    .put("connectionId", instanceModel.getConnection().getId());
            jdbc.update("UPDATE review_job SET config_snapshot=CAST(? AS jsonb) WHERE id=?", snapshot.toString(), id);
        }
        var openPool = new LlmCallPool();
        openPool.setSlug("open-" + UUID.randomUUID());
        openPool.setDisplayName("Available endpoint");
        openPool.setMaxConcurrentCalls(2);
        openPool = pools.saveAndFlush(openPool);
        var openConnection = llmConnectionRepository.saveAndFlush(LlmCatalogTestFixtures.connection("open-endpoint"));
        var openModel = LlmCatalogTestFixtures.model(openConnection, "open-model", "open-upstream");
        openModel = llmModelRepository.saveAndFlush(openModel);
        jdbc.update("UPDATE llm_model SET call_pool_id=? WHERE id=?", openPool.getId(), openModel.getId());
        var openBinding = bindingRepository
                .findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        openWorkspace.getId(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED)
                .orElseThrow();
        openBinding.setInstanceModel(openModel);
        bindingRepository.saveAndFlush(openBinding);
        UUID runnable = queuedJob(openWorkspace, old.plusSeconds(100));
        var openSnapshot = objectMapper.createObjectNode();
        openSnapshot
                .putObject("model")
                .put("connectionScope", "INSTANCE")
                .put("modelId", openModel.getId())
                .put("connectionId", openConnection.getId());
        jdbc.update(
                "UPDATE review_job SET config_snapshot=CAST(? AS jsonb) WHERE id=?", openSnapshot.toString(), runnable);
        long blockedId = blocked.getId();
        UUID oldest = Objects.requireNonNull(first);
        var candidates = jobRepository.findQueuedIdsStartedFirst(1, Set.of(blockedId), Set.of());
        assertThat(candidates).containsExactly(runnable);
        assertThat(jobRepository.findById(oldest).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.QUEUED);
        jdbc.update(
                "UPDATE review_job SET attempt=1, admission_attempt=0, admission_digest=repeat('a',64) WHERE id=?",
                oldest);
        assertThat(jobRepository.findQueuedIdsStartedFirst(1, Set.of(blockedId), Set.of()))
                .containsExactly(oldest);
    }

    @Test
    @DisplayName("a workspace that already runs reviews still offers its next one: capacity, not a count, paces it")
    void shouldOfferAReviewWhileItsWorkspaceAlreadyRunsOthers() {
        Workspace busy = activeWorkspace("busy-ws");
        binding(busy);
        runningJob(busy);
        runningJob(busy);
        UUID queued = queuedJob(busy, Instant.now());

        List<UUID> candidates = jobRepository.findQueuedIdsStartedFirst(10);

        assertThat(candidates).contains(queued);
    }

    @Test
    void shouldOfferStartedWorkBeforeFreshReviewsQueuedEarlier() {
        Workspace ws = activeWorkspace("started-first-ws");
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID fresh = queuedJob(ws, base);
        UUID alsoFresh = queuedJob(ws, base.plusSeconds(1));
        // A drained attempt and an admitted review whose composition was handed on, both requeued later.
        UUID retried = queuedJob(ws, base.plusSeconds(10));
        jdbc.update("UPDATE review_job SET attempt = 1 WHERE id = ?", retried);
        UUID admitted = queuedJob(ws, base.plusSeconds(20));
        jdbc.update("UPDATE review_job SET admission_attempt=0, admission_digest=repeat('a',64) WHERE id=?", admitted);

        List<UUID> candidates = jobRepository.findQueuedIdsStartedFirst(10).stream()
                .filter(List.of(fresh, alsoFresh, retried, admitted)::contains)
                .toList();

        assertThat(candidates).containsExactly(retried, admitted, fresh, alsoFresh);
    }

    @Test
    void purgedWorkspaceJobsCannotBePolledOrClaimed() {
        Workspace workspace = activeWorkspace("purged-ws");
        UUID jobId = queuedJob(workspace, Instant.now());
        workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(jobRepository.findQueuedIdsStartedFirst(10)).doesNotContain(jobId);
        Optional<ReviewJob> claimed = transactionTemplate.execute(
                status -> jobRepository.findByIdQueuedForUpdateSkipLocked(jobId, Instant.now()));
        assertThat(claimed).isEmpty();
    }

    @Test
    void shouldOfferAnIdleWorkspaceBeforeOneWithRunningReviews() {
        var busy = activeWorkspace("busy-priority");
        var idle = activeWorkspace("idle-priority");
        binding(busy);
        binding(idle);
        runningJob(busy);
        runningJob(busy);
        queuedJob(busy, Instant.now().minusSeconds(3600));
        UUID expected = queuedJob(idle, Instant.now().minusSeconds(60));

        assertThat(jobRepository.findQueuedIdsStartedFirst(1)).containsExactly(expected);
    }

    @Test
    void shouldGiveAnotherWorkspaceABatchPlaceWhenOneWorkspaceHasAnOlderBacklog() {
        var busy = activeWorkspace("batch-busy");
        var other = activeWorkspace("batch-other");
        binding(busy);
        binding(other);
        Instant old = Instant.now().minusSeconds(3600);
        UUID first = queuedJob(busy, old);
        UUID second = queuedJob(busy, old.plusSeconds(1));
        for (int i = 2; i < 5; i++) queuedJob(busy, old.plusSeconds(i));
        UUID expected = queuedJob(other, Instant.now().minusSeconds(60));

        assertThat(jobRepository.findQueuedIdsStartedFirst(3)).containsExactly(first, expected, second);
    }

    @Test
    void shouldOfferARequestedReviewBeforeAnOlderBackfill() {
        var workspace = activeWorkspace("origin-priority");
        binding(workspace);
        UUID backfill = queuedJob(workspace, Instant.now().minusSeconds(60));
        UUID requested = queuedJob(workspace, Instant.now().minusSeconds(30));
        origin(backfill, ReviewOrigin.BACKFILL);
        origin(requested, ReviewOrigin.REQUEST);
        jdbc.update("UPDATE review_job SET available_at=clock_timestamp()-interval '1 minute' WHERE id=?", backfill);
        jdbc.update("UPDATE review_job SET available_at=clock_timestamp() WHERE id=?", requested);

        assertThat(jobRepository.findQueuedIdsStartedFirst(1)).containsExactly(requested);
    }

    @Test
    void shouldAgeBackfillAheadOfNewRequestsAfterThirtyMinutes() {
        var workspace = activeWorkspace("aged-priority");
        binding(workspace);
        UUID backfill = queuedJob(workspace, Instant.now().minusSeconds(3600));
        UUID requested = queuedJob(workspace, Instant.now().minusSeconds(30));
        origin(backfill, ReviewOrigin.BACKFILL);
        origin(requested, ReviewOrigin.REQUEST);
        jdbc.update("UPDATE review_job SET available_at=clock_timestamp()-interval '31 minutes' WHERE id=?", backfill);
        jdbc.update("UPDATE review_job SET available_at=clock_timestamp() WHERE id=?", requested);

        assertThat(jobRepository.findQueuedIdsStartedFirst(1)).containsExactly(backfill);
    }

    @Test
    void shouldNotCountCompositionAgainstWorkspaceReviewOccupancy() {
        var composing = activeWorkspace("composing-priority");
        var idle = activeWorkspace("idle-beside-composition");
        binding(composing);
        binding(idle);
        UUID running = runningJob(composing);
        jdbc.update("UPDATE review_job SET admission_attempt=0, admission_digest=repeat('a',64) WHERE id=?", running);
        UUID expected = queuedJob(composing, Instant.now().minusSeconds(3600));
        queuedJob(idle, Instant.now().minusSeconds(60));

        assertThat(jobRepository.findQueuedIdsStartedFirst(1)).containsExactly(expected);
    }

    private void origin(UUID job, ReviewOrigin origin) {
        assertThat(jdbc.update(
                        "UPDATE review SET origin=? WHERE id=(SELECT review_id FROM review_job WHERE id=?)",
                        origin.name(),
                        job))
                .isEqualTo(1);
    }

    private Workspace activeWorkspace(String slug) {
        return workspaceRepository.save(TestEntities.activeWorkspace(slug));
    }

    private void binding(Workspace workspace) {
        binding(workspace, DataHandlingTier.UNDECLARED, true);
    }

    private void binding(Workspace workspace, DataHandlingTier tier, boolean enabled) {
        WorkspaceAgentBinding binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setDataHandlingTier(tier);
        binding.setInstanceModel(instanceModel);
        binding.setEnabled(enabled);
        bindingRepository.saveAndFlush(binding);
    }

    private UUID runningJob(Workspace workspace) {
        return runningJob(workspace, null);
    }

    private UUID runningJob(Workspace workspace, @Nullable DataHandlingTier tier) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setLeaseId(UUID.randomUUID());
        job.setConfigSnapshot(snapshot(tier));
        return jobRepository
                .saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job))
                .getId();
    }

    private UUID queuedJob(Workspace workspace, Instant submittedAt) {
        return queuedJob(workspace, submittedAt, null);
    }

    private UUID queuedJob(Workspace workspace, Instant submittedAt, @Nullable DataHandlingTier tier) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.QUEUED);
        job.setConfigSnapshot(snapshot(tier));
        // submitted_at is immutable after insertion, so the fixture must set it before the first flush.
        job.setSubmittedAt(submittedAt);
        job.setAvailableAt(submittedAt);
        UUID jobId = jobRepository
                .saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job))
                .getId();
        // The candidate query compares available_at with the database clock; an older submission keeps its order.
        jdbc.update(
                "UPDATE review_job SET available_at = LEAST(submitted_at, now() - interval '1 second') WHERE id = ?",
                jobId);
        return jobId;
    }

    private ObjectNode snapshot(@Nullable DataHandlingTier tier) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        if (tier != null) {
            snapshot.putObject("model").put("dataHandlingTier", tier.name());
        }
        return snapshot;
    }
}
