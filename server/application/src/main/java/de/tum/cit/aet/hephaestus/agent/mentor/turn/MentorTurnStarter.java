package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.config.AgentBindingLimits;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetExhaustedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUnpricedUsageBlockedException;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
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
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * Starts the next queued turn for a worker: claims it, then admits it. Admission runs at the start, not at
 * submission, because a follow-up can wait while the member's AI choice or the budget changes. A turn admission
 * declines ends {@code FAILED} with the reason the developer reads.
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
    private final MentorAttemptUsageService attemptUsage;
    private final MeterRegistry meters;
    private final LlmConnectionHealth connections;

    /** A claimed and admitted turn attempt: what its host runs, for whom, with which model. */
    public record StartedTurn(MentorTurn turn, long accountId, MentorLlmConfig llm) {}

    /**
     * Claims and admits the next turn. Each step commits on its own, so a declined admission is recorded even
     * though the claim already committed.
     *
     * @return empty when no turn is waiting, or when admission declined the claimed one
     */
    public Optional<StartedTurn> startNext(Duration lease) {
        Optional<MentorTurn> claimed = turns.claimNext(lease);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = claimed.get();
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
        // With the admission, the attempt's usage measurement: no call of the attempt is routed before both exist. An
        // erasure that suppressed the account after the eligibility read above refuses both.
        boolean admitted = attemptUsage.admit(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                MentorTurnAdmission.of(
                        llm,
                        Objects.requireNonNull(llm.priceSnapshot(), "A conversation turn needs an admitted price")));
        if (!admitted) {
            return decline(turn, PERSON_DATA_HELD);
        }
        return Optional.of(new StartedTurn(turn, accountId, llm));
    }

    private Optional<StartedTurn> decline(MentorTurn turn, String reason) {
        turns.end(
                turn.getWorkspaceId(),
                turn.getId(),
                turn.getAttempt(),
                Objects.requireNonNull(turn.getLeaseId()),
                new MentorTurnService.Ending(MentorTurn.Status.FAILED, List.of(), reason));
        log.info(
                "conversation turn {} attempt {} was declined at its start: {}",
                turn.getId(),
                turn.getAttempt(),
                reason);
        meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", "failed").increment();
        return Optional.empty();
    }
}
