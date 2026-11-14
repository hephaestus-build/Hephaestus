package de.tum.cit.aet.hephaestus.agent.mentor.host;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
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
import org.springframework.web.ErrorResponseException;

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
        if (!copyFence.tryHoldForCapture()) {
            var busy = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
            busy.setDetail("Conversation context is being erased");
            busy.getHeaders().set("Retry-After", "1");
            throw busy;
        }
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

    /**
     * Checks the subject and current lease in the transaction that writes a tool's proposal. It holds the sandbox, turn
     * and run rows until it commits, so the run's renewal and the reaper wait for it: only short checked writes belong
     * here. A read that can take longer, such as the turn's context, uses {@link #read}.
     */
    public <T> T write(SandboxRun run, boolean ending, Function<TurnAttempt, T> proposal) {
        return Objects.requireNonNull(transactions.execute(status -> {
            if (!run.kind().conversation() || run.sandbox().kind() != AgentSandboxKind.CONVERSATION)
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
     * Reads for the run's attempt without holding the sandbox, turn or run row while the read runs, as {@code GET
     * …/context} requires ({@link de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind#context}). A context build can
     * take longer than the run's lease, and the renewal and the reaper lock the run's row. The fences and the lease are
     * checked in a short transaction before the read and again after it. The read runs outside any transaction, so each
     * part opens its own and holds its locks only as long as that part reads. A person erasure that completed after the attempt started fails the attempt, as its next partial answer
     * would ({@code MentorTurnService#updatePartial}), so no read that an erasure overtook is answered.
     */
    public <T> T read(SandboxRun run, Function<TurnAttempt, T> reader) {
        TurnAttempt turn = write(run, false, held -> held);
        T value = reader.apply(turn);
        write(run, false, held -> {
            // write() holds capture admission in this transaction, so the answer holds until it commits.
            Instant startedAt = attemptOf(run).getStartedAt();
            if (startedAt == null || copyFence.erasedSince(MentorEntryRepository.COPIES_PERSON_DATA_STORE, startedAt))
                throw new StaleRunException(run.fence());
            return held;
        });
        return value;
    }

    /**
     * The attempt that a sandbox's run drives, while the run's claim holds it: what its tools act for.
     *
     * @param ending whether the call ends the attempt, which a stop or a passed work deadline still allows
     * @throws StaleRunException when the claim no longer holds the run, or its turn ended
     * @throws StopException when the attempt must stop, for a call that does not end it
     */
    public TurnAttempt held(SandboxRun run, boolean ending) {
        MentorTurnRepository.RunAttempt held = attemptOf(run);
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
        Instant deadline = attemptOf(run).getDeadline();
        if (deadline == null) throw new IllegalStateException("A started turn's run has a deadline");
        return deadline.minus(TurnRunsAdapter.GRACE);
    }

    /** The run start and physical sandbox reuse, for the first-token metric. */
    public AgentSandboxes.RunStart start(SandboxRun run) {
        return sandboxes.start(run.sandbox(), run.fence()).orElseThrow(() -> new StaleRunException(run.fence()));
    }

    private MentorTurnRepository.RunAttempt attemptOf(SandboxRun run) {
        AgentRunFence fence = run.fence();
        return rows.findRunAttempt(fence.workspaceId(), fence.runId(), fence.epoch())
                .orElseThrow(() -> new StaleRunException(fence));
    }

    private static boolean stopped(MentorTurnRepository.RunAttempt held) {
        return MentorTurn.Status.STOPPED.name().equals(held.getStatus());
    }
}
