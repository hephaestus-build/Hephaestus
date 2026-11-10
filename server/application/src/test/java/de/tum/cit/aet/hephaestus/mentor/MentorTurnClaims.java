package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims the next turn as a worker does, without its admission: the capped claim of the turn's run, then the turn's
 * start, in one transaction ({@code MentorTurnStarter}).
 */
@TestComponent
public class MentorTurnClaims {

    private final AgentRuns runs;
    private final AgentRunReaper reaper;
    private final MentorTurnService turns;
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    MentorTurnClaims(
            AgentRuns runs,
            AgentRunReaper reaper,
            MentorTurnService turns,
            TransactionTemplate transactions,
            JdbcTemplate jdbc) {
        this.runs = runs;
        this.reaper = reaper;
        this.turns = turns;
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    /** Starts the next turn that a claim offers, without a cap. */
    public Optional<MentorTurn> claimNext() {
        return claimNext(Integer.MAX_VALUE);
    }

    /** Starts the next turn that a claim offers while fewer than {@code cap} turns are leased. */
    public Optional<MentorTurn> claimNext(int cap) {
        return Objects.requireNonNull(transactions.execute(status -> runs.claimCapped(AgentRunKind.TURN, cap)
                .flatMap(lease -> {
                    Optional<MentorTurn> started = turns.start(lease.workspaceId(), lease.runId(), lease.epoch());
                    if (started.isEmpty()) status.setRollbackOnly();
                    return started;
                })));
    }

    /** Starts this turn, as a claim of its conversation's next run does. */
    public MentorTurn claim(MentorTurn turn) {
        return Objects.requireNonNull(transactions.execute(status -> runs.claimSubject(
                                turn.getWorkspaceId(),
                                "conversation:" + turn.getConversationId(),
                                Set.of(AgentRunKind.TURN))
                        .flatMap(lease -> turns.start(lease.workspaceId(), lease.runId(), lease.epoch()))))
                .orElseThrow(() -> new IllegalStateException("The turn's run was not the next of its conversation"));
    }

    /** The holder of the turn is gone: its run's lease runs out, and the run reaper takes the turn back. */
    public void takeBack(MentorTurn turn) {
        expireLease(turn);
        reaper.reap();
    }

    /**
     * Takes the turn back as the run reaper does, without billing the lost attempt: the turn returns to the queue for
     * its retry, or fails when its run spent it, and its run moves with it, in one transaction.
     */
    public MentorTurnService.LostAttempt takeBackUnbilled(MentorTurn turn) {
        expireLease(turn);
        return Objects.requireNonNull(transactions.execute(status -> {
            MentorTurn locked =
                    turns.lockByRun(turn.getWorkspaceId(), turn.getRunId()).orElseThrow();
            Integer spent =
                    jdbc.queryForObject("SELECT attempt FROM agent_run WHERE id = ?", Integer.class, turn.getRunId());
            MentorTurnService.LostAttempt lost = turns.takeBack(locked, Objects.requireNonNull(spent) < 1);
            if (locked.getStatus() == MentorTurn.Status.QUEUED)
                jdbc.update(
                        "UPDATE agent_run SET state = 'QUEUED', leased_until = NULL, deadline = NULL,"
                                + " attempt = attempt + 1, available_at = clock_timestamp() WHERE id = ?",
                        turn.getRunId());
            else
                jdbc.update(
                        "UPDATE agent_run SET state = 'ENDED', leased_until = NULL, stop_reason = 'ATTEMPTS_EXHAUSTED',"
                                + " attempt = attempt + 1, ended_at = clock_timestamp() WHERE id = ?",
                        turn.getRunId());
            return lost;
        }));
    }

    /** Lets the lease of the turn's run run out, as a holder that is gone does. */
    public void expireLease(MentorTurn turn) {
        jdbc.update(
                "UPDATE agent_run SET leased_until = ? WHERE id = ? AND state = 'LEASED'",
                Timestamp.from(Instant.now().minusSeconds(1)),
                turn.getRunId());
    }
}
