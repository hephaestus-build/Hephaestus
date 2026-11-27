package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ReviewJobRetentionIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReviewCompositionBasisRepository basisRepository;

    @Autowired
    private WorkPlaceHandoffRepository handoffs;

    @Autowired
    private JdbcTemplate runtimeJdbc;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void shouldCommitBothOfTwoSimultaneousHandoffInsertsOfOneReview() throws Exception {
        assertThat(racingFailures()).isEmpty();
    }

    private List<String> racingFailures() throws Exception {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("handoff-race"));
        ReviewJob job = jobRepository.saveAndFlush(oldTerminalJob(workspace));
        long workspaceId = job.getWorkspaceId();
        // The id the enrolment derives: both seals of one review insert the same primary key.
        UUID id = UUID.nameUUIDFromBytes(
                ("work-place-handoff:" + workspaceId + ":" + job.getReviewId()).getBytes(StandardCharsets.UTF_8));
        var row = WorkPlaceHandoffRow.builder()
                .id(id)
                .workspaceId(workspaceId)
                .reviewId(job.getReviewId())
                .jobId(job.getId())
                .itemId(7L)
                .producer(WorkPlaceHandoffRow.Producer.LEGACY)
                .payload(objectMapper.createObjectNode().put("version", 1))
                .payloadDigest("d".repeat(64))
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .createdAt(Instant.now())
                .build();
        var failures = new CopyOnWriteArrayList<String>();
        // About half of the rounds collided on the primary key before the fix.
        int rounds = 200;
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < rounds; round++) {
                int current = round;
                runtimeJdbc.update(
                        "DELETE FROM work_place_handoff WHERE workspace_id=? AND review_id=?",
                        workspaceId,
                        job.getReviewId());
                var barrier = new CyclicBarrier(2);
                Runnable seal = () -> {
                    try {
                        transactions.executeWithoutResult(status -> {
                            // Like the seal: each transaction checks, sees no committed row, then inserts.
                            handoffs.existsByWorkspaceIdAndReviewId(workspaceId, job.getReviewId());
                            try {
                                barrier.await(30, TimeUnit.SECONDS);
                            } catch (Exception broken) {
                                throw new IllegalStateException(broken);
                            }
                            handoffs.insertOnce(row);
                        });
                    } catch (RuntimeException failed) {
                        failures.add("round " + current + ": " + failed.getMessage());
                    }
                };
                var first = CompletableFuture.runAsync(seal, executor);
                var second = CompletableFuture.runAsync(seal, executor);
                CompletableFuture.allOf(first, second).get(60, TimeUnit.SECONDS);
                assertThat(runtimeJdbc.queryForObject(
                                "SELECT count(*) FROM work_place_handoff WHERE workspace_id=? AND review_id=?",
                                Integer.class,
                                workspaceId,
                                job.getReviewId()))
                        .isOne();
            }
        }
        return failures.stream().limit(3).toList();
    }

    @Test
    @Transactional
    void removesOriginalCompositionInputsOnceNoWriterCanUseThem() {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("basis-retention"));
        Workspace other = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("basis-other"));
        ReviewJob ended = oldTerminalJob(workspace);
        ReviewJob running = oldJob(workspace, ReviewJobStatus.RUNNING);
        ReviewJob expired = oldJob(workspace, ReviewJobStatus.RUNNING);
        jobRepository.saveAllAndFlush(List.of(ended, running, expired));
        Instant now = Instant.now();
        basisRepository.save(basis(ended, now.plus(Duration.ofHours(1))));
        basisRepository.save(basis(running, now.plus(Duration.ofHours(1))));
        basisRepository.save(basis(expired, now.minus(Duration.ofSeconds(1))));

        assertThat(basisRepository.deleteUnusable(now, 100)).isEqualTo(2);

        assertThat(basisRepository.findByWorkspaceIdAndJobId(workspace.getId(), running.getId()))
                .isPresent();
        assertThat(basisRepository.findByWorkspaceIdAndJobId(other.getId(), running.getId()))
                .isEmpty();
        assertThat(basisRepository.findByWorkspaceIdAndJobId(workspace.getId(), ended.getId()))
                .isEmpty();
        assertThat(basisRepository.findByWorkspaceIdAndJobId(workspace.getId(), expired.getId()))
                .isEmpty();
    }

    @Test
    @Transactional
    void shouldDeleteAWorkPlaceHandoffAfterItsExpiryAndWithItsJob() {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("handoff-retention"));
        ReviewJob expired = oldTerminalJob(workspace);
        ReviewJob live = oldTerminalJob(workspace);
        ReviewJob deleted = oldTerminalJob(workspace);
        jobRepository.saveAllAndFlush(List.of(expired, live, deleted));
        Instant now = Instant.now();
        UUID expiredId = handoff(expired, now.minusSeconds(1));
        UUID liveId = handoff(live, now.plus(Duration.ofHours(1)));
        UUID deletedId = handoff(deleted, now.plus(Duration.ofHours(1)));
        Objects.requireNonNull(entityManager).flush();

        handoffs.deleteExpired(now, 1000);
        runtimeJdbc.update("DELETE FROM review_job WHERE id=?", deleted.getId());

        assertThat(runtimeJdbc.queryForList(
                        "SELECT id FROM work_place_handoff WHERE id IN (?, ?, ?)",
                        UUID.class,
                        expiredId,
                        liveId,
                        deletedId))
                .containsExactly(liveId);
    }

    private UUID handoff(ReviewJob job, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        handoffs.insertOnce(WorkPlaceHandoffRow.builder()
                .id(id)
                .workspaceId(job.getWorkspaceId())
                .reviewId(job.getReviewId())
                .jobId(job.getId())
                .itemId(7L)
                .itemVersion("2222222222222222222222222222222222222222")
                .producer(WorkPlaceHandoffRow.Producer.LEGACY)
                .payload(objectMapper.createObjectNode().put("version", 1))
                .payloadDigest("d".repeat(64))
                .expiresAt(expiresAt)
                .createdAt(Instant.now())
                .build());
        return id;
    }

    @Test
    @Transactional
    void shouldPreserveTheOriginalInputDigestThroughPostgresJsonb() {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("basis-json"));
        ReviewJob job = jobRepository.saveAndFlush(oldTerminalJob(workspace));
        var original = Map.of(
                "z.md",
                "Original α\n".getBytes(StandardCharsets.UTF_8),
                "a.json",
                "{\"original\":true}".getBytes(StandardCharsets.UTF_8));
        var payload = objectMapper.createObjectNode();
        var files = payload.putObject("files");
        original.forEach((path, bytes) -> files.put(path, Base64.getEncoder().encodeToString(bytes)));
        var kept = basis(job, Instant.now().plus(Duration.ofHours(1)));
        kept.setPayload(payload);
        kept.setBasisDigest(ReviewCompositionBasis.digest(original));
        basisRepository.save(kept);
        var persistence = Objects.requireNonNull(entityManager);
        persistence.flush();
        persistence.clear();

        var reloaded = basisRepository
                .findByWorkspaceIdAndJobId(workspace.getId(), job.getId())
                .orElseThrow();
        var decoded = new TreeMap<String, byte[]>();
        reloaded.getPayload()
                .path("files")
                .properties()
                .forEach(entry -> decoded.put(
                        entry.getKey(),
                        Base64.getDecoder().decode(entry.getValue().asString())));
        assertThat(ReviewCompositionBasis.digest(decoded)).isEqualTo(kept.getBasisDigest());
        original.forEach((path, bytes) -> assertThat(decoded.get(path)).containsExactly(bytes));
    }

    @Test
    @Transactional
    void shouldKeepUnsettledJobsAndStripThenDeleteOnlySettledOnes() {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("payload-retention"));
        var pending = oldTerminalJob(workspace);
        pending.setProcessingStatus(ProcessingStatus.PENDING);
        pending.setOutput(objectMapper.createObjectNode().put("receipt", "needed"));
        var unsettled = oldTerminalJob(workspace);
        unsettled.setProcessingStatus(ProcessingStatus.NONE);
        unsettled.setOutput(objectMapper.createObjectNode().put("receipt", "not yet settled"));
        var finished = oldTerminalJob(workspace);
        finished.setOutput(objectMapper.createObjectNode().put("receipt", "delivered"));
        jobRepository.saveAllAndFlush(List.of(pending, unsettled, finished));
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        jobRepository.stripTerminalPayloads(cutoff, 500);
        assertThat(jobRepository.findById(pending.getId()).orElseThrow().getOutput())
                .isNotNull();
        assertThat(jobRepository.findById(unsettled.getId()).orElseThrow().getOutput())
                .isNotNull();
        assertThat(jobRepository.findById(finished.getId()).orElseThrow().getOutput())
                .isNull();
        assertThat(jobRepository.deleteUnreferencedTerminalRowsOlderThan(cutoff, 500))
                .extracting(ReviewJobRepository.DeletedJob::getRunId)
                .contains(finished.getRunId())
                .doesNotContain(pending.getRunId(), unsettled.getRunId());
        assertThat(runtimeJdbc.queryForList(
                        "SELECT id FROM review_job WHERE id IN (?, ?, ?)",
                        UUID.class,
                        pending.getId(),
                        unsettled.getId(),
                        finished.getId()))
                .containsExactlyInAnyOrder(pending.getId(), unsettled.getId());
    }

    @Test
    @Transactional
    void shouldBlockAPurgeUntilEveryEndedJobIsSettled() {
        Workspace workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("purge-unsettled"));
        var ended = oldJob(workspace, ReviewJobStatus.CANCELLED);
        ended.setCompletedAt(Instant.now().minus(Duration.ofDays(1)));
        ended = jobRepository.saveAndFlush(ended);
        assertThat(ended.getProcessingStatus()).isEqualTo(ProcessingStatus.NONE);

        assertThat(jobRepository.existsPurgeBlockingWork(workspace.getId())).isTrue();

        ended.setProcessingStatus(ProcessingStatus.FAILED);
        jobRepository.saveAndFlush(ended);
        assertThat(jobRepository.existsPurgeBlockingWork(workspace.getId())).isFalse();
    }

    private ReviewCompositionBasis basis(ReviewJob job, Instant expiresAt) {
        var basis = new ReviewCompositionBasis();
        basis.setJobId(job.getId());
        basis.setWorkspaceId(job.getWorkspaceId());
        basis.setAdmissionAttempt(0);
        basis.setAdmissionDigest("a".repeat(64));
        basis.setBasisVersion(1);
        basis.setBasisDigest("b".repeat(64));
        basis.setCopyId(UUID.randomUUID());
        basis.setExpiresAt(expiresAt);
        basis.setPayload(objectMapper.createObjectNode());
        return basis;
    }

    private ReviewJob oldTerminalJob(Workspace workspace) {
        return oldJob(workspace, ReviewJobStatus.COMPLETED);
    }

    private ReviewJob oldJob(Workspace workspace, ReviewJobStatus status) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        ReviewJobFixtures.withStatus(job, status);
        if (status == ReviewJobStatus.COMPLETED) {
            job.setProcessingStatus(ProcessingStatus.DONE);
            job.setCompletedAt(Instant.now().minus(Duration.ofDays(100)));
        }
        job.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return RuntimeJobTestData.persistParent(runtimeJdbc, job);
    }
}
