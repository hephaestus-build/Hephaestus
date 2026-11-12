package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewJobTerminalStorageIntegrationTest extends BaseIntegrationTest {

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    /** A terminal write ends its run in the same transaction, as the executor's does. */
    private int ended(int updated, ReviewJob job) {
        RuntimeJobTestData.agree(jdbc, job.getId());
        return updated;
    }

    private ReviewJob running(String slug) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(slug));
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setConfigSnapshot(new JsonMapper().createObjectNode());
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.prePersist();
        return jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    @Test
    void shouldKeepTheReviewAndPinnedInputWhenRecordingTheTerminalState() {
        var job = running("runtime-terminal-owner");
        var input = job.getInput();
        assertThat(transactions.<Integer>execute(status -> ended(
                        jobs.transitionStatusOwnedBy(
                                job.getId(),
                                ReviewJobStatus.COMPLETED,
                                Instant.now(),
                                null,
                                job.getRunId(),
                                job.getAttempt()),
                        job)))
                .isEqualTo(1);
        var stored =
                jobs.findByIdAndWorkspaceId(job.getId(), job.getWorkspaceId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReviewJobStatus.COMPLETED);
        assertThat(stored.getReviewId()).isEqualTo(job.getReviewId());
        assertThat(stored.getInput()).isEqualTo(input);
        assertThat(stored.getCompletedAt()).isNotNull();
    }

    @Test
    void shouldRefuseATerminalWriteFromAnotherWorker() {
        var job = running("runtime-terminal-foreign");
        assertThat(transactions.<Integer>execute(status -> jobs.transitionStatusOwnedBy(
                        job.getId(), ReviewJobStatus.FAILED, Instant.now(), "failed", OTHER, job.getAttempt())))
                .isZero();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
    }

    @Test
    void shouldRefuseATerminalWriteFromAnEarlierAttemptOfTheSameWorker() {
        var job = running("runtime-terminal-stale");
        int stale = job.getAttempt();
        // The run was requeued and the same worker claimed the job again.
        RuntimeJobTestData.reclaim(jdbc, job);

        assertThat(transactions.<Integer>execute(status -> jobs.transitionStatusOwnedBy(
                        job.getId(), ReviewJobStatus.FAILED, Instant.now(), "failed", job.getRunId(), stale)))
                .isZero();
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(stored.getAttempt()).isEqualTo(stale + 1);
    }

    @Test
    void shouldStartTheSandboxOnlyForTheAttemptTheLedgerRuns() {
        var job = running("runtime-start-requeued");
        int stale = job.getAttempt();
        // The run was requeued and the same worker claimed the job again; the requeue cleared the start.
        RuntimeJobTestData.reclaim(jdbc, job);
        jdbc.update("UPDATE review_job SET execution_started_at = NULL WHERE id = ?", job.getId());

        assertThat(transactions.<Integer>execute(
                        status -> jobs.markExecutionStarted(job.getId(), job.getRunId(), stale, Instant.now())))
                .isZero();
        assertThat(executionStartedAt(job)).isNull();

        assertThat(transactions.<Integer>execute(
                        status -> jobs.markExecutionStarted(job.getId(), job.getRunId(), stale + 1, Instant.now())))
                .isEqualTo(1);
        assertThat(executionStartedAt(job)).isNotNull();
    }

    private @Nullable Timestamp executionStartedAt(ReviewJob job) {
        return jdbc.queryForObject(
                "SELECT execution_started_at FROM review_job WHERE id = ?", Timestamp.class, job.getId());
    }

    @Test
    void shouldPreserveAdmissionFactsWhenALostWriterEnds() {
        var job = running("runtime-terminal-admitted");
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("a".repeat(64));
        jobs.saveAndFlush(job);
        assertThat(transactions.<Integer>execute(status -> ended(
                        jobs.transitionStatusOwnedBy(
                                job.getId(), ReviewJobStatus.FAILED, Instant.now(), "lost", job.getRunId(), 0),
                        job)))
                .isEqualTo(1);
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getAdmissionAttempt()).isZero();
        assertThat(stored.getAdmissionDigest()).isEqualTo("a".repeat(64));
        assertThat(stored.getAttempt()).isZero();
        assertThat(stored.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
    }
}
