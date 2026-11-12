package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Reads the turn held by a sandbox's current run. No turn state lives in the gateway replica. */
@RequiredArgsConstructor
public class HephTurns {
    static final String COULD_NOT_FINISH = "Hephaestus could not finish this reply. Try again.";
    static final String MODEL_STOPPED_ANSWERING =
            "Hephaestus's AI model stopped responding, so Hephaestus could not finish this reply. Try again in a moment.";

    public enum Stop {
        DEVELOPER,
        DEADLINE
    }

    public static final class StopException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Stop reason;

        StopException(Stop reason) {
            super("The turn attempt must stop: " + reason.name().toLowerCase(Locale.ROOT), null, false, false);
            this.reason = reason;
        }

        public Stop reason() {
            return reason;
        }
    }

    private final MentorTurnRepository rows;
    private final MentorConversationRepository conversations;
    private final PersonDataWriteFence writeFence;
    private final PersonDataCopyFence copyFence;
    private final Clock clock;
    private final AgentRuns runs;
    private final MentorTurnService turns;
    private final AgentSandboxes sandboxes;
    private final TransactionTemplate transactions;

    /** An erasure holds this fence exclusively while it changes the account and revokes the sandbox. */
    public void beforeWrite(AgentSandboxes.LiveSandbox sandbox) {
        if (!copyFence.tryHoldForCapture())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation context is being erased");
        if (sandbox.kind() != AgentSandboxKind.CONVERSATION
                || !sandbox.subjectKey().startsWith("conversation:"))
            throw new IllegalArgumentException("A turn belongs to a conversation sandbox");
        UUID conversationId = UUID.fromString(sandbox.subjectKey().substring("conversation:".length()));
        var account = conversations.findAccountIdByIdAndWorkspaceId(conversationId, sandbox.workspaceId());
        // A suppressed or erased account can still end its run. The domain end omits its person data.
        account.ifPresent(accountId -> {
            var unused = writeFence.holdForAccountWrite(accountId);
        });
    }

    /** Checks the subject and current lease in the transaction that writes a tool's proposal. */
    public <T> T write(SandboxRun run, boolean ending, Function<TurnAttempt, T> proposal) {
        return Objects.requireNonNull(transactions.execute(status -> {
            if (run.kind() != AgentRunKind.TURN || run.sandbox().kind() != AgentSandboxKind.CONVERSATION)
                throw new StaleRunException(run.fence());
            beforeWrite(run.sandbox());
            if (!sandboxes.hold(run.sandbox())) throw new StaleRunException(run.fence());
            if (turns.lockByRun(run.fence().workspaceId(), run.fence().runId()).isEmpty())
                throw new StaleRunException(run.fence());
            runs.requireCurrent(run.fence());
            return proposal.apply(held(run, ending));
        }));
    }

    /**
     * The attempt that a sandbox's run drives, while the run's claim holds it: what its tools act for.
     *
     * @param ending whether the call ends the attempt, which a stop or a passed work deadline still allows
     * @throws StaleRunException when the claim no longer holds the run, or its turn ended
     * @throws StopException when the attempt must stop, for a call that does not end it
     */
    public TurnAttempt held(SandboxRun run, boolean ending) {
        MentorTurnRepository.RunAttempt held = read(run);
        if (!ending) {
            if (stopped(held)) throw new StopException(Stop.DEVELOPER);
            Instant deadline = held.getDeadline();
            if (deadline == null) throw new IllegalStateException("A started turn's run has a deadline");
            // The run ends a grace after the work deadline, so its holder can still end what it wrote.
            if (!clock.instant().isBefore(deadline.minus(TurnRunsAdapter.GRACE)))
                throw new StopException(Stop.DEADLINE);
        }
        AgentRunFence fence = run.fence();
        return new TurnAttempt(
                held.getId(),
                fence.workspaceId(),
                held.getAccountId(),
                held.getConversationId(),
                fence.epoch(),
                fence.runId());
    }

    /** When the run's attempt must have done its work: its run's deadline, less the grace to end what it wrote. */
    public Instant workDeadline(SandboxRun run) {
        Instant deadline = read(run).getDeadline();
        if (deadline == null) throw new IllegalStateException("A started turn's run has a deadline");
        return deadline.minus(TurnRunsAdapter.GRACE);
    }

    /** The run start and physical sandbox reuse, for the first-token metric. */
    public AgentSandboxes.RunStart start(SandboxRun run) {
        return sandboxes.start(run.sandbox(), run.fence()).orElseThrow(() -> new StaleRunException(run.fence()));
    }

    private MentorTurnRepository.RunAttempt read(SandboxRun run) {
        AgentRunFence fence = run.fence();
        return rows.findRunAttempt(fence.workspaceId(), fence.runId(), fence.epoch())
                .orElseThrow(() -> new StaleRunException(fence));
    }

    private static boolean stopped(MentorTurnRepository.RunAttempt held) {
        return MentorTurn.Status.STOPPED.name().equals(held.getStatus());
    }
}
