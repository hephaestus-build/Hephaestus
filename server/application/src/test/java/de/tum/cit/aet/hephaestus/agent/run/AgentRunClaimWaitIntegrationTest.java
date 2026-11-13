package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The wait of a claim runs from the run's {@code available_at}, and a claim that did not commit records none. */
class AgentRunClaimWaitIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentRunRepository repository;

    @Autowired
    private AgentRuns bean;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AgentRuns runs;
    private long workspace;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        runs = new AgentRuns(repository, bean.capacity(), meters);
        workspace = workspaces
                .save(TestEntities.activeWorkspace("claim-wait-" + UUID.randomUUID()))
                .getId();
    }

    @Test
    void shouldStartWithATimerForEveryKindAndBand() {
        for (AgentRunKind kind : AgentRunKind.values())
            for (AgentRunBand band : AgentRunBand.values())
                assertThat(timer(kind, band).count()).isZero();
    }

    @Test
    void shouldRecordTheWaitFromAvailableAtUnderTheRunsKindAndBandOnlyAfterTheCommit() {
        AgentRunRef review = enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:waited");
        AgentRunRef turn = enqueue(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "conversation:waited");
        due(review, "90 seconds");
        due(turn, "12 seconds");

        transactions.executeWithoutResult(status -> {
            assertThat(runs.claimSubject(workspace, "review:waited", Set.of(AgentRunKind.REVIEW)))
                    .isPresent();
            assertThat(timer(AgentRunKind.REVIEW, AgentRunBand.BATCH).count())
                    .as("the claim is not committed yet")
                    .isZero();
        });
        transactions.executeWithoutResult(
                status -> assertThat(runs.claimSubject(workspace, "conversation:waited", Set.of(AgentRunKind.TURN)))
                        .isPresent());

        Timer reviews = timer(AgentRunKind.REVIEW, AgentRunBand.BATCH);
        assertThat(reviews.count()).isOne();
        assertThat(reviews.totalTime(TimeUnit.SECONDS)).isBetween(90.0, 300.0);
        Timer turns = timer(AgentRunKind.TURN, AgentRunBand.INTERACTIVE);
        assertThat(turns.count()).isOne();
        assertThat(turns.totalTime(TimeUnit.SECONDS)).isBetween(12.0, 300.0);
        assertThat(timer(AgentRunKind.REVIEW, AgentRunBand.BACKGROUND).count()).isZero();
    }

    @Test
    void shouldRecordNothingForAClaimThatRolledBack() {
        AgentRunRef run = enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:rolled-back");
        due(run, "60 seconds");

        transactions.executeWithoutResult(status -> {
            assertThat(runs.claimSubject(workspace, "review:rolled-back", Set.of(AgentRunKind.REVIEW)))
                    .isPresent();
            status.setRollbackOnly();
        });

        assertThat(timer(AgentRunKind.REVIEW, AgentRunBand.BATCH).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM agent_run WHERE id = ?", String.class, run.id()))
                .isEqualTo("QUEUED");
    }

    @Test
    void shouldCountARequeuedRunsNextClaimFromItsNewAvailableAt() {
        AgentRunRef run = enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:requeued");
        due(run, "10 minutes");
        AgentRunLease first = Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, "review:requeued", Set.of(AgentRunKind.REVIEW))))
                .orElseThrow();
        transactions.executeWithoutResult(
                status -> runs.requeue(first.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null));

        transactions.executeWithoutResult(
                status -> assertThat(runs.claimSubject(workspace, "review:requeued", Set.of(AgentRunKind.REVIEW)))
                        .isPresent());

        Timer reviews = timer(AgentRunKind.REVIEW, AgentRunBand.BATCH);
        assertThat(reviews.count()).isEqualTo(2);
        assertThat(reviews.max(TimeUnit.SECONDS))
                .as("the first claim waited ten minutes")
                .isGreaterThanOrEqualTo(600);
        assertThat(reviews.totalTime(TimeUnit.SECONDS))
                .as("the second claim waited from the requeue, not from the first available_at")
                .isLessThan(600.0 + 60.0);
    }

    private Timer timer(AgentRunKind kind, AgentRunBand band) {
        return meters.get(AgentMetrics.AGENT_RUN_CLAIM_WAIT)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .timer();
    }

    private AgentRunRef enqueue(AgentRunKind kind, AgentRunBand band, String subject) {
        return Objects.requireNonNull(
                transactions.execute(status -> runs.enqueue(workspace, kind, band, subject, null)));
    }

    private void due(AgentRunRef run, String ago) {
        jdbc.update("UPDATE agent_run SET available_at = now() - CAST(? AS interval) WHERE id = ?", ago, run.id());
    }
}
