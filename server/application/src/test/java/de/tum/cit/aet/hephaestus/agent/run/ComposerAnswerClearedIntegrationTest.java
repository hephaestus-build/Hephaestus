package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.composer.ComposerTranscript;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A served conversation keeps its answer while the claim that it answered holds the run, and no longer: the run's
 * trigger clears it on every way the run leaves that lease, so the transcript holds content only during the live
 * claim. Each case asserts on the rows.
 */
class ComposerAnswerClearedIntegrationTest extends BaseIntegrationTest {

    private static final byte[] ANSWER = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ComposerTranscript transcript;

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

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaces
                .save(TestEntities.activeWorkspace(
                        "answer-" + UUID.randomUUID().toString().substring(0, 8)))
                .getId();
    }

    @Test
    void shouldClearTheAnswerWhenItsHolderEndsTheRun() {
        AgentRunLease lease = served();
        transactions.executeWithoutResult(status -> runs.end(lease.fence(), AgentRunStopReason.COMPLETED));
        assertCleared(lease);
    }

    @Test
    void shouldClearTheAnswerWhenTheRunIsRequeued() {
        AgentRunLease lease = served();
        transactions.executeWithoutResult(
                status -> runs.requeue(lease.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null));
        assertCleared(lease);
    }

    @Test
    void shouldClearTheAnswerWhenTheRunIsDeferred() {
        AgentRunLease lease = served();
        transactions.executeWithoutResult(status -> runs.defer(lease.fence(), AgentRuns.Deferral.MODEL_POOL));
        assertCleared(lease);
    }

    @Test
    void shouldClearTheAnswerWhenTheRunIsCancelled() {
        AgentRunLease lease = served();
        transactions.executeWithoutResult(status -> runs.cancel(new AgentRunRef(lease.runId(), workspace)));
        assertCleared(lease);
    }

    @Test
    void shouldClearTheAnswerWhenTheReaperQueuesAnExpiredRunAgain() {
        AgentRunLease lease = served();
        reap(lease, 5);
        assertThat(state(lease)).isEqualTo("QUEUED");
        assertCleared(lease);
    }

    @Test
    void shouldClearTheAnswerWhenTheReaperEndsAnExpiredRun() {
        AgentRunLease lease = served();
        reap(lease, 0);
        assertThat(state(lease)).isEqualTo("ENDED");
        assertCleared(lease);
    }

    @Test
    void shouldClearTheEndedClaimsAnswerAndKeepTheAnswerOfTheClaimThatHoldsTheRunNow() {
        AgentRunLease first = served();
        transactions.executeWithoutResult(
                status -> runs.requeue(first.fence(), AgentRuns.Charge.FREE, Duration.ZERO, null));
        AgentRunLease second = claim(first.runId());
        serve(second);

        assertThat(second.epoch()).isGreaterThan(first.epoch());
        assertCleared(first);
        assertThat(answer(second)).as("the claim that holds the run").isEqualTo(ANSWER);
    }

    @Test
    void shouldRecordNoAnswerThatArrivesAfterItsClaimEnded() {
        AgentRunLease lease = composing();
        transactions.executeWithoutResult(status -> runs.cancel(new AgentRunRef(lease.runId(), workspace)));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> transcript.served(
                        workspace,
                        lease.runId(),
                        lease.epoch(),
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        new ComposerTranscript.Forward("conversation-1", "ON_WORK"),
                        ANSWER)))
                .isInstanceOf(StaleRunException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM composer_transcript_part WHERE run_id = ?", Integer.class, lease.runId()))
                .as("the late answer leaves no part, so the trigger has nothing it missed")
                .isZero();
    }

    /** A compose claim that served one conversation, and kept its answer. */
    private AgentRunLease served() {
        AgentRunLease lease = composing();
        serve(lease);
        assertThat(answer(lease)).as("the live claim keeps the answer").isEqualTo(ANSWER);
        return lease;
    }

    private AgentRunLease composing() {
        String subject = "review:" + UUID.randomUUID();
        AgentRunRef run = Objects.requireNonNull(transactions.execute(
                status -> runs.enqueue(workspace, AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BATCH, subject, null)));
        return claim(run.id());
    }

    private AgentRunLease claim(UUID runId) {
        String subject = Objects.requireNonNull(
                jdbc.queryForObject("SELECT concurrency_key FROM agent_run WHERE id = ?", String.class, runId));
        return Objects.requireNonNull(transactions.execute(
                        status -> runs.claimSubject(workspace, subject, Set.of(AgentRunKind.REVIEW_COMPOSE))))
                .orElseThrow();
    }

    private void serve(AgentRunLease lease) {
        jdbc.update(
                "INSERT INTO composer_transcript_part (id, workspace_id, run_id, lease_epoch, kind, digest, answer)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, 'SERVED', 'conversation-1', ?)",
                workspace,
                lease.runId(),
                lease.epoch(),
                ANSWER);
    }

    /** Expires the claim's lease and lets the reaper take it back; it ends the run once no retry is left. */
    private void reap(AgentRunLease lease, int retries) {
        jdbc.update("UPDATE agent_run SET leased_until = now() - interval '1 second' WHERE id = ?", lease.runId());
        var handler = new AgentRunHandler() {
            @Override
            public AgentRunKind kind() {
                return AgentRunKind.REVIEW_COMPOSE;
            }

            @Override
            public int retries() {
                return retries;
            }

            @Override
            public void lock(AgentRunRef run) {}

            @Override
            public LossOutcome lost(AgentRunFence fence) {
                return new Requeue();
            }

            @Override
            public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {}
        };
        new AgentRunReaper(repository, transactions, List.of(handler), new SimpleMeterRegistry(), Clock.systemUTC())
                .reap();
    }

    private void assertCleared(AgentRunLease lease) {
        assertThat(answer(lease)).as("the ended claim keeps no content").isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM composer_transcript_part"
                                + " WHERE run_id = ? AND lease_epoch = ? AND kind = 'SERVED'",
                        Integer.class,
                        lease.runId(),
                        lease.epoch()))
                .as("the digest stays, so the claim's conversation is still known")
                .isOne();
    }

    private byte @Nullable [] answer(AgentRunLease lease) {
        var rows = jdbc.queryForList(
                "SELECT answer FROM composer_transcript_part WHERE run_id = ? AND lease_epoch = ? AND kind = 'SERVED'",
                lease.runId(),
                lease.epoch());
        assertThat(rows).hasSize(1);
        return (byte[]) rows.getFirst().get("answer");
    }

    private @Nullable String state(AgentRunLease lease) {
        return jdbc.queryForObject("SELECT state FROM agent_run WHERE id = ?", String.class, lease.runId());
    }
}
