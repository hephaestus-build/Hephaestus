package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnStarter;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnAttempts;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Claims from the ledger on a pull. A gateway holds no session or worker-local attempt authority. */
@Service
@ConditionalOnWorkerRole
@Profile(ConversationAssignments.PROFILES)
public class ConversationAssignments {
    static final String PROFILES = "!specs & !cds-training";
    static final String TOOK_TOO_LONG = "Hephaestus took too long to reply and stopped. Try again.";
    static final String COULD_NOT_FINISH = "Hephaestus could not finish this reply. Try again.";
    static final String MODEL_STOPPED_ANSWERING =
            "Hephaestus’s AI model stopped responding, so Hephaestus could not finish this reply. Try again in a moment.";

    private final MentorTurnStarter starter;
    private final MentorTurnService turns;
    private final MentorTurnAttempts attempts;
    /** How long past its deadline a loop may still renew an attempt, to end it with what it has. */
    static final Duration END_GRACE = Duration.ofSeconds(30);

    private final AttemptTokenIssuer issuer;
    private final Clock clock;
    private final Duration lease;

    public ConversationAssignments(
            MentorTurnStarter starter,
            MentorTurnService turns,
            MentorTurnAttempts attempts,
            AttemptTokenIssuer issuer,
            Clock clock,
            @Value("${hephaestus.mentor.turn.lease:PT1M}") Duration lease) {
        this.starter = starter;
        this.turns = turns;
        this.attempts = attempts;
        this.issuer = issuer;
        this.clock = clock;
        this.lease = lease;
    }

    public record AssignmentDTO(UUID turnId, int attempt, String token, long renewAfterMs, Instant deadline) {}

    public Optional<AssignmentDTO> next() {
        return starter.startNext(lease).map(started -> {
            MentorTurn turn = started.turn();
            Instant deadline = Objects.requireNonNull(turn.getStartedAt())
                    .plusSeconds(started.llm().timeoutSeconds());
            UUID leaseId = Objects.requireNonNull(turn.getLeaseId());
            String token = issuer.issueForTurn(
                    turn.getId(), turn.getWorkspaceId(), turn.getAttempt(), leaseId, deadline.plus(lease));
            return new AssignmentDTO(
                    turn.getId(), turn.getAttempt(), token, Math.max(100, lease.toMillis() / 3), deadline);
        });
    }

    public MentorTurn held(AttemptJwt token) {
        return attempts.require(token.workspaceId(), token.id(), token.attempt(), token.leaseId());
    }

    public TurnAttempt running(AttemptJwt token) {
        MentorTurn turn = held(token);
        if (turn.getStatus() != MentorTurn.Status.RUNNING)
            throw new MentorLeaseLostException(token.id(), token.attempt());
        return identity(turn);
    }

    public TurnAttempt identity(MentorTurn turn) {
        return new TurnAttempt(
                turn.getId(),
                turn.getWorkspaceId(),
                turns.accountId(turn),
                turn.getConversationId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                Objects.requireNonNull(turn.getContextGeneration()));
    }

    /**
     * Renews the attempt's lease, but not past its deadline and the grace its loop has to end it: an attempt whose loop
     * does not end it in time loses its lease, and the lease reaper takes it back.
     *
     * @throws MentorLeaseLostException when the attempt no longer holds its lease or ran past its deadline
     */
    public MentorTurn renew(AttemptJwt token) {
        MentorTurn row = held(token);
        if (!clock.instant().isBefore(deadline(row).plus(END_GRACE))) {
            throw new MentorLeaseLostException(token.id(), token.attempt());
        }
        return turns.renewLease(token.workspaceId(), token.id(), token.attempt(), token.leaseId(), lease);
    }

    /** Sends the attempt's newest partial answer to its readers; nothing stores it. */
    public void partial(AttemptJwt token, JsonNode partial) {
        turns.updatePartial(token.workspaceId(), token.id(), token.attempt(), token.leaseId(), partial);
    }

    public Instant deadline(TurnAttempt turn) {
        return deadline(attempts.require(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.leaseId()));
    }

    private static Instant deadline(MentorTurn row) {
        MentorTurnAdmission admitted =
                MentorTurnAdmission.read(row.getAdmission()).orElseThrow();
        return Objects.requireNonNull(row.getStartedAt()).plusSeconds(admitted.timeoutSeconds());
    }
}
