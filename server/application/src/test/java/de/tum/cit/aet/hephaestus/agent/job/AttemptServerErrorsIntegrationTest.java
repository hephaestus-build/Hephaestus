package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.job.AttemptServerErrors.Request;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** What a failed attempt's error says comes from rows the server wrote itself, not from the sandbox's files. */
class AttemptServerErrorsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    @Autowired
    private LlmUsageEventRepository usageEvents;

    @Test
    void shouldKeepTheServersOwnErrorOnTheRunningAttemptOnly() {
        ReviewJob job = runningJob();
        var errors = new AttemptServerErrors(jobs, mapper, transactions);
        var identity = new AdmissionIdentity(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), Objects.requireNonNull(job.getRunId()));

        errors.record(
                new AdmissionIdentity(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt() + 1,
                        Objects.requireNonNull(job.getRunId())),
                Request.ADMIT,
                new IllegalStateException("another attempt"));
        assertThat(capture(job)).as("another attempt writes nothing").doesNotContain("serverError");

        errors.record(identity, Request.STAGE, new DataAccessResourceFailureException("connection to db-host lost"));

        ReviewJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(AttemptServerErrors.describe(stored.getCapture(), stored.getAttempt()))
                .isEqualTo("The server answered the review's results with its own error when it staged a result: a"
                        + " database operation failed.");
        assertThat(capture(job)).as("never the exception's message").doesNotContain("db-host");
    }

    @Test
    void shouldSayThatNothingRanOnlyWhileTheLedgerHoldsNoCallOfTheAttempt() {
        ReviewJob job = runningJob();
        var noPractice = new AgentResult(false, Map.of(PiResultParser.PRACTICES_EVALUATED, 0));

        assertThat(ReviewAttemptResults.factsOf(job, noPractice, usageEvents).nothingRan())
                .isTrue();

        // A call that the proxy opened and the sandbox never reported still counts.
        jdbc.update("""
                INSERT INTO llm_usage_event (id, workspace_id, run_id, lease_epoch, call_state, job_type,
                    source_type, source_id, source_attempt, review_id, model_kind, pricing_state, funding_source,
                    cost_usd, started_at, occurred_at)
                SELECT gen_random_uuid(), j.workspace_id, j.run_id, r.lease_epoch, 'OPEN', 'PRACTICE_REVIEW',
                    'REVIEW_JOB', j.id, r.lease_epoch - 1, j.review_id, 'CHAT', 'PRICED', 'INSTANCE', 0, now(), now()
                FROM review_job j JOIN agent_run r ON r.id = j.run_id WHERE j.id = ?
                """, job.getId());

        assertThat(ReviewAttemptResults.factsOf(job, noPractice, usageEvents).nothingRan())
                .isFalse();
    }

    @Test
    void shouldKeepAReviewProgramsCrashOnTheRunningAttemptOnly() {
        ReviewJob job = runningJob();
        UUID run = Objects.requireNonNull(job.getRunId());

        assertThat(jobs.recordProgramFailure(
                        job.getId(), job.getWorkspaceId(), job.getAttempt() + 1, run, "{\"failure\":\"init:Error\"}"))
                .as("another attempt writes nothing")
                .isZero();
        assertThat(jobs.recordProgramFailure(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        run,
                        "{\"attempt\":" + job.getAttempt() + ",\"failure\":\"init:TypeError\"}"))
                .isOne();

        ReviewJob stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(RunnerEnd.describeProgramFailure(stored.getCapture(), job.getAttempt()))
                .contains("(TypeError)");
    }

    private ReviewJob runningJob() {
        String name = "server-error-" + UUID.randomUUID().toString().substring(0, 8);
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(name));
        var work = modelFixtures.work(
                workspace, name, "chat.thread", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000L));
        var job = work.job();
        job.setStatus(ReviewJobStatus.RUNNING);
        return RuntimeJobTestData.save(new TransactionTemplate(transactions), jdbc, jobs, job);
    }

    private String capture(ReviewJob job) {
        return Objects.requireNonNullElse(
                jdbc.queryForObject("SELECT capture::text FROM review_job WHERE id = ?", String.class, job.getId()),
                "");
    }
}
