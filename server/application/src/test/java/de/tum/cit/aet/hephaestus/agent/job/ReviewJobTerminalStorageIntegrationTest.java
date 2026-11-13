package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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

    @Autowired
    private AgentRuns runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @ParameterizedTest
    @CsvSource({"COMPLETED,COMPLETED", "FAILED,FAILED", "TIMED_OUT,DEADLINE", "CANCELLED,CANCELLED"})
    void shouldEndTheRunWithItsReviewsTerminalOutcome(ReviewJobStatus status, AgentRunStopReason reason) {
        var job = running(
                "runtime-outcome-" + status.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        var fence = RuntimeJobTestData.fence(jdbc, job);
        var terminal = new ReviewJobTerminal(jobs, runs, mock(ReviewCompositionContinuation.class));

        assertThat(transactions.<Integer>execute(
                        tx -> terminal.transition(job.getId(), fence, status, Instant.now(), null)))
                .isEqualTo(1);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(jdbc.queryForObject(
                        "SELECT stop_reason FROM agent_run WHERE id=? AND workspace_id=?",
                        String.class,
                        job.getRunId(),
                        job.getWorkspaceId()))
                .isEqualTo(reason.name());
        assertThat(transactions.<AgentRuns.EndOutcome>execute(tx -> runs.end(fence, reason)))
                .isEqualTo(AgentRuns.EndOutcome.ALREADY_ENDED);
    }

    @Test
    void shouldPreserveEvidenceRefusalAndEndItsRunAsFailed() {
        var job = running("runtime-evidence-refused");
        var fence = RuntimeJobTestData.fence(jdbc, job);
        var terminal = new ReviewJobTerminal(jobs, runs, mock(ReviewCompositionContinuation.class));
        var refusal = new JsonMapper().createObjectNode().put("outcome", "INSUFFICIENT_EVIDENCE");

        assertThat(transactions.<Integer>execute(tx -> terminal.refuse(job.getId(), fence, refusal)))
                .isEqualTo(1);
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(stored.getOutput()).isEqualTo(refusal);
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM agent_run WHERE id=?", String.class, job.getRunId()))
                .isEqualTo("FAILED");
    }

    @Test
    void shouldRollbackTheReviewEndWhenItsRunFenceIsLost() {
        var job = running("runtime-outcome-stale");
        var stale = mock(AgentRuns.class);
        when(stale.end(any(), any())).thenReturn(AgentRuns.EndOutcome.STALE);
        var terminal = new ReviewJobTerminal(jobs, stale, mock(ReviewCompositionContinuation.class));
        var fence = RuntimeJobTestData.fence(jdbc, job);

        assertThatThrownBy(() -> transactions.execute(tx ->
                        terminal.transition(job.getId(), fence, ReviewJobStatus.FAILED, Instant.now(), "failure")))
                .isInstanceOf(StaleRunException.class);
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
        assertThat(jdbc.queryForObject("SELECT state FROM agent_run WHERE id=?", String.class, job.getRunId()))
                .isEqualTo("LEASED");
    }

    @Test
    void shouldRecordAFailedRunWhenItsCapturedInputsCannotBeRestored() {
        var job = running("runtime-capture-failed");
        var failure =
                new CheckoutUnverifiedException(CheckoutUnverifiedException.Reason.UNAVAILABLE, "No captured checkout");
        var failures = new AttemptCaptureFailures(
                jobs, runs, mock(ReviewJobTelemetry.class), new JsonMapper(), transactionManager);
        var identity = new AdmissionIdentity(job.getId(), job.getWorkspaceId(), job.getAttempt(), job.getRunId());

        assertThat(failures.fail(identity, failure)).isTrue();
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM agent_run WHERE id=?", String.class, job.getRunId()))
                .isEqualTo("FAILED");
    }

    @Test
    void shouldRefuseACompletedRunWhenTheReviewFailed() {
        var job = running("runtime-outcome-agreement");
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
                    jobs.transitionStatusOwnedBy(
                            job.getId(), ReviewJobStatus.FAILED, Instant.now(), null, job.getRunId(), job.getAttempt());
                    jdbc.update(
                            "UPDATE agent_run SET state='ENDED', leased_until=NULL, stop_reason='COMPLETED', ended_at=clock_timestamp() WHERE id=?",
                            job.getRunId());
                }))
                .hasStackTraceContaining("a review that did not complete cannot have a completed run");
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ReviewJobStatus.RUNNING);
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
