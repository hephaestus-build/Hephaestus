package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The attempts that the conversation loop acts for, each read under its complete lease fence. */
@Service
public class MentorTurnAttempts {

    private final MentorTurnRepository turns;

    public MentorTurnAttempts(MentorTurnRepository turns) {
        this.turns = turns;
    }

    /** A running attempt's identity and the context it may read now. */
    public record Live(long workspaceId, UUID turnId, int attempt, UUID leaseId, UUID contextGeneration) {}

    /** Every attempt that holds its live lease now, of every workspace. */
    @Transactional(readOnly = true)
    public List<Live> live() {
        return turns.findLiveAttempts().stream()
                .map(row -> new Live(
                        row.getWorkspaceId(),
                        row.getId(),
                        row.getAttempt(),
                        row.getLeaseId(),
                        row.getContextGeneration()))
                .toList();
    }

    /**
     * The attempt, locked, while it holds its live lease on the database clock.
     *
     * @throws EntityNotFoundException when the workspace has no such turn: a credential of another workspace reaches
     *     none
     * @throws MentorLeaseLostException when the attempt no longer holds its lease
     */
    @Transactional
    public MentorTurn require(long workspaceId, UUID turnId, int attempt, UUID leaseId) {
        MentorTurn turn = turns.lockByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        return fenced(turns, turn, attempt, leaseId);
    }

    /** The turn, if this attempt holds its live lease on the database clock. */
    static MentorTurn fenced(MentorTurnRepository turns, MentorTurn turn, int attempt, UUID leaseId) {
        Instant expires = turn.getLeasedUntil();
        if (!turn.isLeased()
                || turn.getAttempt() != attempt
                || !leaseId.equals(turn.getLeaseId())
                || expires == null
                || !expires.isAfter(turns.databaseClock())) {
            throw new MentorLeaseLostException(turn.getId(), attempt);
        }
        return turn;
    }
}
