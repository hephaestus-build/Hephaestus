package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The run gauges read PostgreSQL: runs in each state, hold and band, and the ones that have ended. */
class AgentRunSamplerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunRepository repository;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private long workspace;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        // The gauges count every run of the instance, so no run of another test may remain.
        databaseTestUtils.cleanDatabase();
        workspace = workspaces
                .save(TestEntities.activeWorkspace("sampler-" + UUID.randomUUID()))
                .getId();
    }

    @Test
    void shouldCountRunsByKindBandStateAndHoldAndMeasureOnlyTheDueOnes() {
        // Two due batch reviews, one for ten minutes and one for two.
        dueSince(enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:old"), "10 minutes");
        dueSince(enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:new"), "2 minutes");
        // A retry's backoff: queued, no hold, not due yet.
        AgentRunRef backoff = enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:backoff");
        jdbc.update("UPDATE agent_run SET available_at = now() + interval '1 hour' WHERE id = ?", backoff.id());
        // Held runs: not due, whatever their available_at says.
        AgentRunRef budget = enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:budget");
        hold(budget, AgentRunHold.BUDGET);
        AgentRunRef room = enqueue(AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BACKGROUND, "review:room");
        hold(room, AgentRunHold.SANDBOX_ROOM);
        // A turn that waits 30 seconds, and a turn that a sandbox holds.
        dueSince(enqueue(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "conversation:waits"), "30 seconds");
        enqueue(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "conversation:leased");
        transactions.executeWithoutResult(
                status -> assertThat(runs.claimSubject(workspace, "conversation:leased", Set.of(AgentRunKind.TURN)))
                        .isPresent());
        // A run that ended leaves every gauge.
        enqueue(AgentRunKind.REVIEW, AgentRunBand.BACKGROUND, "review:ended");
        AgentRunLease finished = Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, "review:ended", Set.of(AgentRunKind.REVIEW))))
                .orElseThrow();
        transactions.executeWithoutResult(status -> runs.end(finished.fence(), AgentRunStopReason.COMPLETED));

        new AgentRunSampler(repository, meters).sample();

        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "none"))
                .as("two due and one in backoff")
                .isEqualTo(3);
        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "budget"))
                .isOne();
        assertThat(runs(AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BACKGROUND, "queued", "sandbox_room"))
                .isOne();
        assertThat(runs(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "queued", "none"))
                .isOne();
        assertThat(runs(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "leased", "none"))
                .isOne();
        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BACKGROUND, "leased", "none"))
                .as("the ended run")
                .isZero();
        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BACKGROUND, "queued", "none"))
                .isZero();

        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH))
                .as("the backoff and the budget hold are not due")
                .isEqualTo(2);
        assertThat(due(AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BACKGROUND))
                .as("a sandbox room hold is not due")
                .isZero();
        assertThat(due(AgentRunKind.TURN, AgentRunBand.INTERACTIVE)).isOne();

        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH))
                .as("the older of the two due reviews")
                .isBetween(600.0, 900.0);
        assertThat(oldestDue(AgentRunKind.TURN, AgentRunBand.INTERACTIVE)).isBetween(30.0, 300.0);
        assertThat(oldestDue(AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BACKGROUND))
                .isZero();
        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.INTERACTIVE)).isZero();
    }

    /** The claim offers no run of a suspended workspace, so such a run is open but is not a stalled queue. */
    @Test
    void shouldNotCountTheRunsOfASuspendedWorkspaceAsDueUntilItIsActiveAgain() {
        dueSince(enqueue(AgentRunKind.REVIEW, AgentRunBand.BATCH, "review:suspended"), "2 hours");
        var sampler = new AgentRunSampler(repository, meters);

        sampler.sample();
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isOne();
        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isGreaterThanOrEqualTo(7200.0);

        jdbc.update("UPDATE workspace SET status = 'SUSPENDED' WHERE id = ?", workspace);
        sampler.sample();
        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "none"))
                .as("the run stays open")
                .isOne();
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isZero();
        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isZero();

        jdbc.update("UPDATE workspace SET status = 'ACTIVE' WHERE id = ?", workspace);
        sampler.sample();
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isOne();
        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isGreaterThanOrEqualTo(7200.0);
    }

    private AgentRunRef enqueue(AgentRunKind kind, AgentRunBand band, String subject) {
        return Objects.requireNonNull(
                transactions.execute(status -> runs.enqueue(workspace, kind, band, subject, null)));
    }

    private void dueSince(AgentRunRef run, String interval) {
        jdbc.update("UPDATE agent_run SET available_at = now() - CAST(? AS interval) WHERE id = ?", interval, run.id());
    }

    private void hold(AgentRunRef run, AgentRunHold reason) {
        assertThat(transactions.<Boolean>execute(status -> runs.hold(run, reason, Duration.ofHours(1))))
                .isTrue();
    }

    private double runs(AgentRunKind kind, AgentRunBand band, String state, String hold) {
        return meters.get(AgentMetrics.AGENT_RUN_RUNS)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .tag("state", state)
                .tag("hold_reason", hold)
                .gauge()
                .value();
    }

    private double due(AgentRunKind kind, AgentRunBand band) {
        return meters.get(AgentMetrics.AGENT_RUN_DUE)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .gauge()
                .value();
    }

    private double oldestDue(AgentRunKind kind, AgentRunBand band) {
        return meters.get(AgentMetrics.AGENT_RUN_OLDEST_DUE_AGE_SECONDS)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .gauge()
                .value();
    }
}
