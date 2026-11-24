package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.authority.spi.AuthorityGenerations;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** A claim that cannot start its run defers it: the run keeps its place and its wait, and is tried less often. */
class AgentRunDeferralIntegrationTest extends BaseIntegrationTest {

    private static final String SUBJECT = "review:deferred";

    @Autowired
    private AgentRunRepository repository;

    @Autowired
    private AgentRuns bean;

    @Autowired
    private AuthorityGenerations generations;

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
        runs = new AgentRuns(repository, bean.capacity(), meters, generations);
        workspace = workspaces
                .save(TestEntities.activeWorkspace("deferral-" + UUID.randomUUID()))
                .getId();
    }

    @Test
    void shouldStartWithACounterForEveryKindAndReason() {
        for (AgentRunKind kind : AgentRunKind.values())
            for (AgentRuns.Deferral reason : AgentRuns.Deferral.values())
                assertThat(deferrals(kind, reason)).isZero();
    }

    @Test
    void shouldKeepADeferredRunDueAndOfferItToNoClaimUntilItsDeferralEnds() {
        AgentRunRef run = enqueue();
        due(run, "40 seconds");
        var dueAt = row(run).get("available_at");

        deferFor(run, claim(), AgentRuns.Deferral.MODEL_POOL);

        var deferred = row(run);
        assertThat(deferred.get("state")).isEqualTo("QUEUED");
        assertThat(deferred.get("available_at"))
                .as("the run stays due since then")
                .isEqualTo(dueAt);
        assertThat(deferred.get("attempt")).as("a deferral spends no attempt").isEqualTo(0);
        // Far ahead, so a slow test cannot reach the end of the deferral before it claims.
        jdbc.update(
                "UPDATE agent_run SET deferred_until = clock_timestamp() + interval '1 hour' WHERE id = ?", run.id());
        assertThat(tryClaim()).as("no claim takes it before its deferral ends").isEmpty();
        assertThat(deferrals(AgentRunKind.REVIEW, AgentRuns.Deferral.MODEL_POOL))
                .isOne();

        jdbc.update("UPDATE agent_run SET deferred_until = clock_timestamp() WHERE id = ?", run.id());
        assertThat(tryClaim()).isPresent();
        assertThat(row(run).get("deferred_until"))
                .as("the claim clears the deferral")
                .isNull();
        Timer waits = meters.get(AgentMetrics.AGENT_RUN_CLAIM_WAIT)
                .tag("kind", "review")
                .tag("band", "batch")
                .timer();
        assertThat(waits.max(TimeUnit.SECONDS))
                .as("the claim after the deferral waited from the first time the run was due")
                .isGreaterThanOrEqualTo(40);
    }

    @Test
    void shouldWaitTheSameIntervalHoweverLongTheRunHasWaited() {
        AgentRunRef run = enqueue();
        due(run, "10 minutes");
        // A run that waited long tries the pool as often as a new one.
        deferFor(run, claim(), AgentRuns.Deferral.LOCKED);
        assertThat(deferrals(AgentRunKind.REVIEW, AgentRuns.Deferral.LOCKED)).isOne();
    }

    @Test
    void shouldCountNothingForADeferralThatRolledBack() {
        AgentRunRef run = enqueue();
        AgentRunLease lease = claim();

        transactions.executeWithoutResult(status -> {
            assertThat(runs.defer(lease.fence(), AgentRuns.Deferral.LOCKED)).isTrue();
            status.setRollbackOnly();
        });

        assertThat(deferrals(AgentRunKind.REVIEW, AgentRuns.Deferral.LOCKED)).isZero();
        assertThat(row(run).get("state")).isEqualTo("LEASED");
    }

    @Test
    void shouldRefuseTheDeferralOfAClaimThatNoLongerHoldsItsRun() {
        AgentRunRef run = enqueue();
        AgentRunLease lease = claim();
        transactions.executeWithoutResult(status -> runs.cancel(run));

        Boolean deferred = transactions.execute(status -> runs.defer(lease.fence(), AgentRuns.Deferral.LOCKED));
        assertThat(deferred).isFalse();
        assertThat(row(run).get("deferred_until")).isNull();
    }

    private AgentRunRef enqueue() {
        return Objects.requireNonNull(transactions.execute(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW, AgentRunBand.BATCH, SUBJECT, null)));
    }

    private AgentRunLease claim() {
        return tryClaim().orElseThrow();
    }

    private Optional<AgentRunLease> tryClaim() {
        return Objects.requireNonNull(
                transactions.execute(status -> runs.claimSubject(workspace, SUBJECT, Set.of(AgentRunKind.REVIEW))));
    }

    /** Defers the run, and checks that it waits {@link AgentRuns#DEFER_WAIT} from the database clock of the deferral. */
    private void deferFor(AgentRunRef run, AgentRunLease lease, AgentRuns.Deferral reason) {
        Timestamp before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        defer(lease, reason);
        Timestamp after = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        Timestamp until =
                jdbc.queryForObject("SELECT deferred_until FROM agent_run WHERE id = ?", Timestamp.class, run.id());
        var wait = AgentRuns.DEFER_WAIT;
        assertThat(Objects.requireNonNull(until).toInstant())
                .isBetween(
                        Objects.requireNonNull(before).toInstant().plus(wait),
                        Objects.requireNonNull(after).toInstant().plus(wait));
    }

    private void defer(AgentRunLease lease, AgentRuns.Deferral reason) {
        Boolean deferred = transactions.execute(status -> runs.defer(lease.fence(), reason));
        assertThat(deferred).isTrue();
    }

    private void due(AgentRunRef run, String ago) {
        jdbc.update("UPDATE agent_run SET available_at = now() - CAST(? AS interval) WHERE id = ?", ago, run.id());
    }

    private Map<String, @Nullable Object> row(AgentRunRef run) {
        return jdbc.queryForMap(
                "SELECT state, attempt, available_at, deferred_until FROM agent_run WHERE id = ?", run.id());
    }

    private double deferrals(AgentRunKind kind, AgentRuns.Deferral reason) {
        return meters.get(AgentMetrics.AGENT_RUN_DEFERRED)
                .tag("kind", AgentRuns.tag(kind))
                .tag("reason", AgentRuns.tag(reason))
                .counter()
                .count();
    }
}
