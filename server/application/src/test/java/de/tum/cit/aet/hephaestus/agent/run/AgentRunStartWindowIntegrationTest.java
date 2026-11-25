package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.testconfig.RunningMigration;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/** Checks the dispatch, wake, lease and reap decisions for the same rows on PostgreSQL. */
@TestPropertySource(properties = {
    "hephaestus.agent.capacity.lease-renewal=1h",
    "hephaestus.agent.capacity.lease-ttl=2h"
})
class AgentRunStartWindowIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String ELAPSE_WINDOW = """
            UPDATE agent_run SET available_at =
                clock_timestamp() - make_interval(secs => start_within_seconds + 1)
            WHERE id = ?
            """;

    private enum QueueState {
        DUE, DEFERRED, BEHIND_PREDECESSOR, PAST_START_WINDOW, PAST_DEADLINE
    }

    @Autowired
    private AgentRuns runs;

    @Autowired
    private AgentRunRepository runRows;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRows;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private long workspace;
    private Conversation conversation;

    @BeforeEach
    void openConversation() {
        var developer = persistUser("start-window");
        workspace = createWorkspace("start-window", "Start window", "start-window-org", AccountType.ORG, developer)
                .getId();
        conversation = conversations.findOrCreateMain(workspace, accountId(developer));
    }

    @ParameterizedTest
    @EnumSource(QueueState.class)
    void shouldAgreeOnWhichQueuedTurnsCanStart(QueueState queueState) {
        var predecessors = queueState == QueueState.BEHIND_PREDECESSOR
                ? List.of(ask(conversation).turn())
                : List.<ConversationTurn>of();
        var excluded = predecessors.stream().map(this::runOf).map(AgentRunRef::id).toList();
        var turn = ask(conversation).turn();
        var run = runOf(turn);
        switch (queueState) {
            case BEHIND_PREDECESSOR, PAST_START_WINDOW -> jdbc.update(ELAPSE_WINDOW, run.id());
            case DEFERRED -> jdbc.update(
                    "UPDATE agent_run SET deferred_until = clock_timestamp() + interval '1 hour' WHERE id = ?", run.id());
            case PAST_DEADLINE -> jdbc.update(
                    "UPDATE agent_run SET deadline = clock_timestamp() - interval '1 minute' WHERE id = ?", run.id());
            case DUE -> {}
        }

        assertStartPaths(turn, AgentRunKind.TURN, queueState == QueueState.DUE, excluded);
        var overdue = runRows.findOverdueQueued(AgentRunKind.TURN.name(), List.of());
        if (queueState == QueueState.PAST_START_WINDOW || queueState == QueueState.PAST_DEADLINE) {
            assertThat(overdue).hasValueSatisfying(row -> {
                assertThat(row.getId()).isEqualTo(run.id());
                assertThat(row.getPastDeadline()).isEqualTo(queueState == QueueState.PAST_DEADLINE);
            });
        } else {
            assertThat(overdue).isEmpty();
        }

        if (queueState == QueueState.PAST_START_WINDOW) assertEndsWithoutStarting(turn);
        if (queueState == QueueState.BEHIND_PREDECESSOR) {
            reaper.reap();
            assertQueuedWithoutLease(turn);
            turns.stop(workspace, predecessors.getFirst().getId());
            assertStartPaths(turn, AgentRunKind.TURN, false, List.of());
            assertEndsWithoutStarting(turn);
        }
    }

    @Test
    void shouldRefuseAnExpiredTurnBeforeReapingWhenTheMigrationBarrierLifts() throws Exception {
        var submitted = ask(conversation);
        var expiring = submitted.turn();
        var control = ask(conversations.openThread(workspace, submitted.question().getId())).turn();
        var expiredRun = runOf(expiring);
        var controlRun = runOf(control);

        try (var migration = RunningMigration.start(dataSource)) {
            assertStartPaths(expiring, AgentRunKind.TURN, false, List.of(controlRun.id()));
            assertStartPaths(control, AgentRunKind.TURN, false, List.of(expiredRun.id()));
            assertThat(runRows.findOverdueQueued(AgentRunKind.TURN.name(), List.of())).isEmpty();
            // Move the due time on the migration session so the window elapses while the barrier is up.
            migration.update(ELAPSE_WINDOW, expiredRun.id());
            assertThat(runRows.findOverdueQueued(AgentRunKind.TURN.name(), List.of()))
                    .map(AgentRunRepository.ExpiredRow::getId)
                    .contains(expiredRun.id());
        }

        assertStartPaths(expiring, AgentRunKind.TURN, false, List.of(controlRun.id()));
        assertStartPaths(control, AgentRunKind.TURN, true, List.of());
        transactions.executeWithoutResult(status -> {
            var lease = runs.claim(Set.of(AgentRunKind.TURN), List.of()).orElseThrow();
            assertThat(lease.runId()).isEqualTo(controlRun.id());
            assertThat(turns.start(workspace, lease.runId(), lease.epoch())).isPresent();
        });
        assertThat(turnRows.findById(control.getId()).orElseThrow().getAttempt()).isOne();
        assertEndsWithoutStarting(expiring);
    }

    @Test
    void shouldRefuseADigestPastItsContactWindowEvenIfItsDueTimeMoved() {
        String requestId = UUID.randomUUID().toString();
        assertThat(turns.submitDigest(
                        workspace,
                        conversation.getId(),
                        new ConversationTurnService.DigestRequest(
                                requestId, "Weekly recap", JsonNodeFactory.instance.objectNode(), Duration.ofSeconds(1))))
                .isTrue();
        var digest = turnRows.findByWorkspaceIdAndRequestId(workspace, requestId).orElseThrow();
        var run = runOf(digest);
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT created_at + make_interval(secs => start_within_seconds) <= clock_timestamp()
                  FROM agent_run WHERE id = ?
                """, Boolean.class, run.id())));
        jdbc.update("UPDATE agent_run SET available_at = clock_timestamp() WHERE id = ?", run.id());

        assertStartPaths(digest, AgentRunKind.DIGEST, false, List.of());
        assertEndsWithoutStarting(digest);
    }

    private void assertStartPaths(
            ConversationTurn turn, AgentRunKind kind, boolean offered, List<UUID> excluded) {
        var run = runOf(turn);
        var kinds = Set.of(kind);
        String subject = "conversation:" + turn.getConversationId();
        assertSoftly(softly -> {
            softly.assertThat(probeLease(() -> runs.claim(kinds, excluded), run))
                    .as("fleet claim")
                    .isEqualTo(offered);
            softly.assertThat(probeLease(() -> runs.claimSubject(workspace, subject, kinds), run))
                    .as("subject claim")
                    .isEqualTo(offered);
            softly.assertThat(runs.due(kinds, 0, 100).stream().anyMatch(row -> row.run().equals(run)))
                    .as("dispatch")
                    .isEqualTo(offered);
            softly.assertThat(runs.dueOf(kinds, List.of(workspace), List.of(subject)).stream()
                            .anyMatch(row -> row.run().equals(run)))
                    .as("gateway wake")
                    .isEqualTo(offered);
        });
        assertQueuedWithoutLease(turn);
    }

    /** Rolls each probe back so both claims and both reads see the same queued row. */
    private boolean probeLease(Supplier<Optional<AgentRunLease>> claim, AgentRunRef run) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            status.setRollbackOnly();
            var lease = claim.get();
            if (lease.isEmpty()) return false;
            assertThat(lease.get().fence()).isEqualTo(new AgentRunFence(run.id(), workspace, 1));
            assertThat(runRows.findByRef(run.id(), workspace).orElseThrow().getState())
                    .isEqualTo(AgentRunState.LEASED);
            return true;
        }));
    }

    private void assertQueuedWithoutLease(ConversationTurn turn) {
        var run = runRows.findByRef(runOf(turn).id(), workspace).orElseThrow();
        assertThat(run.getState()).isEqualTo(AgentRunState.QUEUED);
        assertThat(run.getLeaseEpoch()).isZero();
        assertThat(run.getLeasedUntil()).isNull();
        var queued = turnRows.findById(turn.getId()).orElseThrow();
        assertThat(queued.getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(queued.getAttempt()).isZero();
    }

    private void assertEndsWithoutStarting(ConversationTurn turn) {
        reaper.reap();

        var run = runRows.findByRef(runOf(turn).id(), workspace).orElseThrow();
        assertThat(run.getState()).isEqualTo(AgentRunState.ENDED);
        assertThat(run.getStopReason()).isEqualTo(AgentRunStopReason.NOT_STARTED);
        assertThat(run.getLeaseEpoch()).isZero();
        var ended = turnRows.findById(turn.getId()).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(ended.getAttempt()).isZero();
        assertThat(ended.getFailureCause()).isEqualTo("start_wait_expired");
    }

    private ConversationTurnService.Submission ask(Conversation target) {
        return turns.submit(
                        workspace,
                        target.getId(),
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP, "Question", UUID.randomUUID().toString(), null, null));
    }

    private AgentRunRef runOf(ConversationTurn turn) {
        return new AgentRunRef(Objects.requireNonNull(turn.getRunId()), workspace);
    }
}
