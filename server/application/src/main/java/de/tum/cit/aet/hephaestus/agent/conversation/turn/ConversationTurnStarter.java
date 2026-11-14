package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.config.ModelAssignmentLimits;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationLlmConfig;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.exception.ConversationRefusedException;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetExhaustedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUnpricedUsageBlockedException;
import de.tum.cit.aet.hephaestus.conversation.ConversationLeaseLostException;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admits the turn whose run a conversation sandbox just claimed. Admission runs at the start, not at
 * submission, because a follow-up can wait while the member's AI choice or the budget changes. A turn admission
 * declines ends {@code FAILED} with the reason the developer reads. The sandbox owner holds the run claim.
 */
@Service
@RequiredArgsConstructor
@NamedInterface(name = "conversation-turn")
public class ConversationTurnStarter {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnStarter.class);

    static final String PROVIDER_PAUSED =
            "Hephaestus's AI model is not responding right now, so Hephaestus did not start this reply. Try again in a few minutes.";

    /** The existing generic refusal, also for a turn whose account an erasure suppressed while it was admitted. */
    static final String PERSON_DATA_HELD = "Hephaestus cannot run now.";

    static final String MODEL_TOO_SMALL =
            "Hephaestus's AI model in this workspace is too small to hold a conversation. Ask a workspace owner to choose a larger conversation model.";

    private final ConversationTurnService turns;
    private final ConversationAdmission admission;
    private final DigestAdmission digests;
    private final ConversationTurnRepository turnRows;
    private final PersonDataWriteFence writeFence;
    private final MeterRegistry meters;
    private final LlmConnectionHealth connections;

    /** A claimed and admitted turn attempt: what its host runs, for whom, with which model. */
    public record StartedTurn(ConversationTurn turn, long accountId, ConversationLlmConfig llm) {}

    /**
     * Starts and admits the turn of a run that a sandbox's pull claimed, in the pull's transaction.
     *
     * @return empty when the turn no longer waits, or admission declined it and ended it with its run
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StartedTurn> startClaimed(AgentRunFence claim) {
        return turns.start(claim.workspaceId(), claim.runId(), claim.epoch()).flatMap(this::admitted);
    }

    /** Admits a turn whose attempt just started; a declined one ends {@code FAILED} with its run. */
    private Optional<StartedTurn> admitted(ConversationTurn turn) {
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(turn.getWorkspaceId()));
            MDC.put(StructuredLogKeys.RUN_ID, turn.getRunId().toString());
            MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(turn.getAttempt()));
            MDC.put(StructuredLogKeys.SUBJECT_KEY, "conversation:" + turn.getConversationId());
            MDC.remove(StructuredLogKeys.REVIEW_ID);
            MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
            MDC.remove(StructuredLogKeys.JOB_ID);
            return admitInContext(turn);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private Optional<StartedTurn> admitInContext(ConversationTurn turn) {
        long accountId = turns.accountId(turn);
        if (digests.refuses(turn.getWorkspaceId(), turn.getId())) return decline(turn, DigestAdmission.REFUSED);
        if (turn.getSurface() != ConversationTurn.Surface.SYSTEM)
            meters.counter(AgentMetrics.CONVERSATION_TURN_STARTED).increment();
        Instant startedAt = turn.getStartedAt();
        if (turn.getSurface() != ConversationTurn.Surface.SYSTEM && turn.getAttempt() == 1 && startedAt != null) {
            // A retry waited since its lease expired, which the row does not keep.
            Timer.builder(AgentMetrics.CONVERSATION_TURN_QUEUE_WAIT)
                    .description("From a question to the start of its first attempt")
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(turn.getCreatedAt(), startedAt));
        }
        ConversationLlmConfig llm;
        try {
            llm = admission.admit(turn.getWorkspaceId(), accountId);
        } catch (ConversationRefusedException refused) {
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
        Optional<String> tooSmall =
                ModelAssignmentLimits.tooSmallForConversation(llm.contextWindow(), llm.maxOutputTokens());
        if (tooSmall.isPresent()) {
            log.warn("conversation turn {} cannot run on its model: {}", turn.getId(), tooSmall.get());
            return decline(turn, MODEL_TOO_SMALL);
        }
        // No call of the attempt is routed before its admission exists. An erasure that suppressed the account after
        // the
        // eligibility read above refuses it.
        boolean admitted = admit(
                turn,
                ConversationTurnAdmission.of(
                        llm,
                        Objects.requireNonNull(llm.priceSnapshot(), "A conversation turn needs an admitted price")));
        if (!admitted) {
            return decline(turn, PERSON_DATA_HELD);
        }
        return Optional.of(new StartedTurn(turn, accountId, llm));
    }

    /**
     * Freezes the model and rates in the claim's transaction. The gateway took the account's person-data fence before
     * the sandbox, turn and run locks. Rechecking it here refuses an account whose processing is suppressed.
     *
     * @return false when the account's person data may not be written, because an erasure suppressed it
     */
    private boolean admit(ConversationTurn turn, ConversationTurnAdmission admitted) {
        if (turnRows.findByIdAndWorkspaceId(turn.getId(), turn.getWorkspaceId()).isEmpty())
            throw new ConversationLeaseLostException(turn.getId(), turn.getAttempt());
        boolean writable = turnRows.findAccountIdByIdAndWorkspaceId(turn.getId(), turn.getWorkspaceId())
                .map(writeFence::holdForAccountWrite)
                .orElse(false);
        if (!writable) return false;
        turns.admit(turn.getWorkspaceId(), turn.getId(), turn.getAttempt(), turn.getRunId(), admitted.toJson());
        return true;
    }

    private Optional<StartedTurn> decline(ConversationTurn turn, String reason) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                turn.getRunId(),
                new ConversationTurnService.Ending(ConversationTurn.Status.FAILED, List.of(), null, reason));
        log.atInfo()
                .addKeyValue("turn.surface", turn.getSurface().name().toLowerCase(Locale.ROOT))
                .log(
                        "conversation turn {} attempt {} was declined at its start: {}",
                        turn.getId(),
                        turn.getAttempt(),
                        reason);
        if (turn.getSurface() != ConversationTurn.Surface.SYSTEM)
            meters.counter(AgentMetrics.CONVERSATION_TURN_COMPLETED, "outcome", "failed")
                    .increment();
        return Optional.empty();
    }
}
