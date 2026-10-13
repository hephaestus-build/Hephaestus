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
            "Heph's AI model is not responding right now, so Heph did not start this reply. Try again in a few minutes.";

    static final String MODEL_TOO_SMALL =
            "Heph's AI model in this workspace is too small to hold a conversation. Ask a workspace owner to choose a larger model for Heph.";

    private final MentorTurnService turns;
    private final MentorAdmission admission;
    private final MeterRegistry meters;
    private final LlmConnectionHealth connections;

    /** A claimed and admitted turn attempt: what its host runs, for whom, with which model. */
    public record StartedTurn(MentorTurn turn, long developerId, MentorLlmConfig llm) {}

    /**
     * Claims and admits the next turn. Each step commits on its own, so a declined admission is recorded even
     * though the claim already committed.
     *
     * @return empty when no turn is waiting, or when admission declined the claimed one
     */
    public Optional<StartedTurn> startNext(String workerId, Duration lease) {
        Optional<MentorTurn> claimed = turns.claimNext(workerId, lease);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = claimed.get();
        long developerId = turns.developerId(turn);
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
            llm = admission.admit(turn.getWorkspaceId(), developerId);
        } catch (MentorRefusedException refused) {
            return decline(turn, workerId, refused.reason().userMessage());
        } catch (LlmBudgetExhaustedException | LlmUnpricedUsageBlockedException blocked) {
            return decline(turn, workerId, Objects.requireNonNullElse(blocked.getMessage(), "Heph cannot run now."));
        }
        if (connections.isOpen(llm.connectionScope(), llm.connectionId())) {
            log.info("Heph turn {} was not started: its provider connection is paused after failures", turn.getId());
            return decline(turn, workerId, PROVIDER_PAUSED);
        }
        Optional<String> tooSmall = AgentBindingLimits.tooSmallForHeph(llm.contextWindow(), llm.maxOutputTokens());
        if (tooSmall.isPresent()) {
            log.warn("Heph turn {} cannot run on its model: {}", turn.getId(), tooSmall.get());
            return decline(turn, workerId, MODEL_TOO_SMALL);
        }
        turns.admit(
                turn.getId(),
                turn.getAttempt(),
                workerId,
                MentorTurnAdmission.of(
                                llm, Objects.requireNonNull(llm.priceSnapshot(), "A Heph turn needs an admitted price"))
                        .toJson());
        return Optional.of(new StartedTurn(turn, developerId, llm));
    }

    private Optional<StartedTurn> decline(MentorTurn turn, String workerId, String reason) {
        turns.end(turn.getId(), turn.getAttempt(), workerId, MentorTurn.Status.FAILED, List.of(), reason);
        log.info("Heph turn {} attempt {} was declined at its start: {}", turn.getId(), turn.getAttempt(), reason);
        meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", "failed").increment();
        return Optional.empty();
    }
}
