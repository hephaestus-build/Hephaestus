package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetExhaustedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUnpricedUsageBlockedException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts the next queued turn for a worker: claims its run, then admits it. Admission runs at the start, not at
 * submission, because a follow-up can wait while the member's AI choice or the budget changes. A turn admission
 * declines ends {@code FAILED} with the reason the developer reads. The claim keeps the installation's turn cap
 * ({@code hephaestus.agent.capacity.turns}).
 */
@Service
@RequiredArgsConstructor
@NamedInterface(name = "mentor-turn")
public class MentorTurnStarter {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnStarter.class);

    static final String PROVIDER_PAUSED =
            "Hephaestus's AI model is not responding right now, so Hephaestus did not start this reply. Try again in a few minutes.";

    /** The existing generic refusal, also for a turn whose account an erasure suppressed while it was admitted. */
    static final String PERSON_DATA_HELD = "Hephaestus cannot run now.";

    static final String MODEL_TOO_SMALL =
            "Hephaestus's AI model in this workspace is too small to hold a conversation. Ask a workspace owner to choose a larger conversation model.";

    private final MentorTurnService turns;
    private final MentorAdmission admission;
    private final MentorTurnRepository turnRows;
    private final PersonDataWriteFence writeFence;
    private final MeterRegistry meters;
    private final LlmConnectionHealth connections;
    private final AgentRuns runs;
    private final AgentCapacityProperties capacity;
    private final TransactionTemplate transactions;

    /** A claimed and admitted turn attempt: what its host runs, for whom, with which model. */
    public record StartedTurn(MentorTurn turn, long accountId, MentorLlmConfig llm) {}

    /**
     * Claims and admits the next turn. Each step commits on its own, so a declined admission is recorded even
     * though the claim already committed.
     *
     * @return empty when no turn is waiting, the installation runs its cap of turns, or admission declined the claimed
     *     one
     */
    public Optional<StartedTurn> startNext() {
        var previous = MDC.getCopyOfContextMap();
        try {
            return startInContext();
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private Optional<StartedTurn> startInContext() {
        Integer turnCap = capacity.turns();
        int cap = turnCap == null ? Integer.MAX_VALUE : turnCap;
        Optional<MentorTurn> claimed = Objects.requireNonNull(transactions.execute(status -> runs.claimCapped(
                        AgentRunKind.TURN, cap)
                .flatMap(lease -> {
                    Optional<MentorTurn> started = turns.start(lease.workspaceId(), lease.runId(), lease.epoch());
                    // A stop holds the turn: the claim rolls back, and the stop ends the run.
                    if (started.isEmpty()) status.setRollbackOnly();
                    return started;
                })));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = claimed.get();
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(turn.getWorkspaceId()));
        MDC.put(StructuredLogKeys.RUN_ID, turn.getRunId().toString());
        MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(turn.getAttempt()));
        MDC.put(StructuredLogKeys.SUBJECT_KEY, "conversation:" + turn.getConversationId());
        MDC.remove(StructuredLogKeys.REVIEW_ID);
        MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
        MDC.remove(StructuredLogKeys.JOB_ID);
        long accountId = turns.accountId(turn);
        meters.counter(AgentMetrics.MENTOR_TURN_STARTED).increment();
        Instant startedAt = turn.getStartedAt();
        if (turn.getAttempt() == 1 && startedAt != null) {
            // A retry waited since its lease expired, which the row does not keep.
            Timer.builder(AgentMetrics.MENTOR_TURN_QUEUE_WAIT)
                    .description("From a question to the start of its first attempt")
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(turn.getCreatedAt(), startedAt));
        }
        MentorLlmConfig llm;
        try {
            llm = admission.admit(turn.getWorkspaceId(), accountId);
        } catch (MentorRefusedException refused) {
            return decline(turn, refused.reason().userMessage());
        } catch (LlmBudgetExhaustedException | LlmUnpricedUsageBlockedException blocked) {
            return decline(turn, Objects.requireNonNullElse(blocked.getMessage(), PERSON_DATA_HELD));
        }
        if (connections.isOpen(llm.connectionScope(), llm.connectionId())) {
            log.info(
                    "conversation turn {} was not started: its provider connection is paused after failures",
                    turn.getId());
            return decline(turn, PROVIDER_PAUSED);
        }
        Optional<String> tooSmall = AgentBindingLimits.tooSmallForHeph(llm.contextWindow(), llm.maxOutputTokens());
        if (tooSmall.isPresent()) {
            log.warn("conversation turn {} cannot run on its model: {}", turn.getId(), tooSmall.get());
            return decline(turn, MODEL_TOO_SMALL);
        }
        // No call of the attempt is routed before its admission exists. An erasure that suppressed the account after
        // the
        // eligibility read above refuses it.
        boolean admitted = admit(
                turn,
                MentorTurnAdmission.of(
                        llm,
                        Objects.requireNonNull(llm.priceSnapshot(), "A conversation turn needs an admitted price")));
        if (!admitted) {
            return decline(turn, PERSON_DATA_HELD);
        }
        return Optional.of(new StartedTurn(turn, accountId, llm));
    }

    /**
     * Freezes the model and rates the attempt runs with. Throws when this worker no longer holds the attempt. Takes the
     * person-data write fence of the turn's account first, before any row lock. An erasure holds that fence exclusively
     * while it suppresses the account, so an admission either commits before the suppression, and the erasure detaches
     * the attempt's calls, or it starts after it and is refused here.
     *
     * @return false when the account's person data may not be written, because an erasure suppressed it
     */
    private boolean admit(MentorTurn turn, MentorTurnAdmission admitted) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (turnRows.findByIdAndWorkspaceId(turn.getId(), turn.getWorkspaceId())
                    .isEmpty()) throw new MentorLeaseLostException(turn.getId(), turn.getAttempt());
            boolean writable = turnRows.findAccountIdByIdAndWorkspaceId(turn.getId(), turn.getWorkspaceId())
                    .map(writeFence::holdForAccountWrite)
                    .orElse(false);
            if (!writable) return false;
            turns.admit(turn.getWorkspaceId(), turn.getId(), turn.getAttempt(), turn.getRunId(), admitted.toJson());
            return true;
        }));
    }

    private Optional<StartedTurn> decline(MentorTurn turn, String reason) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                turn.getRunId(),
                new MentorTurnService.Ending(MentorTurn.Status.FAILED, List.of(), null, reason));
        log.info(
                "conversation turn {} attempt {} was declined at its start: {}",
                turn.getId(),
                turn.getAttempt(),
                reason);
        meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", "failed").increment();
        return Optional.empty();
    }
}
