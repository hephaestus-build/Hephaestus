package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageJobType;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.LlmUsageSample;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Ends a turn's attempts: bills each attempt once, when it ends or its lease expires, from the LLM proxy's per-call
 * record on the turn row, and settles the conversational feedback Heph's answer showed
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Delivery of shown feedback).
 */
@Service
@RequiredArgsConstructor
@NamedInterface(name = "mentor-turn")
public class MentorTurnCompletion {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnCompletion.class);

    private final MentorTurnService turns;
    private final ConversationalDeliveryReconciler reconciler;
    private final LlmUsageRecorder usageRecorder;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * Stores the host's newest partial answer.
     *
     * @param startedAt when the attempt started, for its first partial answer, which times the attempt's first token;
     *     {@code null} for every later one
     */
    public void progress(UUID turnId, int attempt, String workerId, JsonNode partial, @Nullable Instant startedAt) {
        turns.updatePartial(turnId, attempt, workerId, partial);
        if (startedAt != null) {
            Timer.builder(AgentMetrics.MENTOR_TURN_FIRST_TOKEN)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(startedAt, clock.instant()));
        }
    }

    /**
     * How an attempt ended: as its host reported, or as the worker decided when the host could not report.
     *
     * @param entries every Pi entry the attempt appended; none when the worker ends it
     * @param error what the developer reads about a failed turn
     * @param cause what the operator reads about it in the worker log: the host's reason and detail, or why the worker
     *     ended the attempt itself
     */
    public record Ending(
            MentorTurn.Status outcome,
            List<JsonNode> entries,
            @Nullable String error,
            @Nullable String cause) {}

    /** Ends the attempt, bills it, settles an app answer's feedback, and logs why a failed attempt failed. */
    @Transactional
    public MentorTurn end(UUID turnId, int attempt, String workerId, Ending ending) {
        MentorTurnService.EndedTurn ended =
                turns.end(turnId, attempt, workerId, ending.outcome(), ending.entries(), ending.error());
        MentorTurn turn = ended.turn();
        if (turn.getStatus() == MentorTurn.Status.FAILED) {
            // After commit: a rolled-back end is repeated by its host and would log the failure twice.
            String cause = ending.cause() == null ? ending.error() : ending.cause();
            TransactionCallbacks.afterCommit(
                    () -> log.warn("Heph turn {} attempt {} failed: {}", turnId, attempt, cause));
        }
        bill(turn, attempt, turn.getAdmission(), ended.proxyUsage(), true);
        settleAppAnswer(turn);
        completed(
                switch (turn.getStatus()) {
                    case STOPPED -> "stopped";
                    case FAILED -> "failed";
                    default -> "success";
                });
        Instant started = turn.getStartedAt();
        Instant finished = turn.getFinishedAt();
        if (started != null && finished != null) {
            Timer.builder(AgentMetrics.MENTOR_TURN_DURATION)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(started, finished));
        }
        return turn;
    }

    /**
     * Settles the feedback of a Slack answer by what reached the developer.
     *
     * @param shownInSlack the observations whose feedback the Slack reply showed; only they are delivered
     */
    @Transactional
    public void recordDelivery(long workspaceId, UUID turnId, Collection<UUID> shownInSlack) {
        answer(workspaceId, turnId).ifPresent(answer -> deliver(answer, shownInSlack::contains));
    }

    /** Suppresses the feedback of an answer that Silent Mode kept from leaving the instance. */
    @Transactional
    public void recordSilenced(long workspaceId, UUID turnId) {
        answer(workspaceId, turnId)
                .ifPresent(answer -> reconciler.suppressForSilentMode(
                        workspaceId, answer.developerId(), answer.shownObservationIds()));
    }

    /** Bills an attempt whose lease expired ({@link MentorTurnService#expireLease}). */
    @Transactional
    public void billLostAttempt(MentorTurnService.LostAttempt lost) {
        MentorTurn turn = lost.turn();
        bill(
                turn,
                lost.attempt(),
                lost.admission(),
                lost.proxyUsage(),
                turn.getStatus().isTerminal());
        if (turn.getStatus() == MentorTurn.Status.QUEUED) {
            meters.counter(AgentMetrics.MENTOR_TURN_RETRIED).increment();
        }
        completed("lease_expired");
    }

    private void completed(String outcome) {
        meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", outcome).increment();
    }

    /** The app shows an answer that ended {@code DONE} or {@code STOPPED}. */
    private void settleAppAnswer(MentorTurn turn) {
        if (turn.getSurface() == MentorTurn.Surface.APP
                && (turn.getStatus() == MentorTurn.Status.DONE || turn.getStatus() == MentorTurn.Status.STOPPED)) {
            answer(turn.getWorkspaceId(), turn.getId()).ifPresent(answer -> deliver(answer, observationId -> true));
        }
    }

    /**
     * Delivers the feedback of the observations that {@code reached} the developer, placed on the message that showed
     * each of them.
     */
    private void deliver(MentorTurnService.Answer answer, Predicate<UUID> reached) {
        Map<UUID, List<UUID>> byMessage = new LinkedHashMap<>();
        answer.shownIn().forEach((observationId, messageId) -> {
            if (reached.test(observationId)) {
                byMessage.computeIfAbsent(messageId, id -> new ArrayList<>()).add(observationId);
            }
        });
        long workspaceId = answer.turn().getWorkspaceId();
        for (Map.Entry<UUID, List<UUID>> shown : byMessage.entrySet()) {
            if (reconciler.reconcile(workspaceId, answer.developerId(), shown.getKey(), shown.getValue()) > 0) {
                return;
            }
        }
    }

    /** Heph's answer to the turn, when it showed feedback. */
    private Optional<MentorTurnService.Answer> answer(long workspaceId, UUID turnId) {
        MentorTurnService.Answer answer = turns.answer(workspaceId, turnId);
        return answer.answerEntryId() == null || answer.shownObservationIds().isEmpty()
                ? Optional.empty()
                : Optional.of(answer);
    }

    /** Appends the attempt's spend to the usage ledger. An attempt that made no call is not billed. */
    private void bill(
            MentorTurn turn, int attempt, @Nullable JsonNode admission, MentorTurnUsage usage, boolean recordOnTurn) {
        if (admission == null || (usage.calls() == 0 && usage.isEmpty())) {
            return;
        }
        // Calls with no token on record: the row names no source rather than crediting one that saw nothing.
        UsageProvenance provenance = usage.isEmpty() ? UsageProvenance.NONE : UsageProvenance.PROXY;
        Optional<MentorTurnAdmission> admitted = MentorTurnAdmission.read(admission);
        LlmPriceSnapshot price = admitted.map(MentorTurnAdmission::price).orElseGet(LlmPriceSnapshot::unpricedInstance);
        String model = admitted.map(MentorTurnAdmission::model).orElse("");
        LlmUsageSample sample = new LlmUsageSample(
                LlmUsageJobType.MENTOR_TURN,
                LlmUsageSourceType.MENTOR_TURN,
                turn.getId(),
                attempt,
                model,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                Math.max(1, usage.calls()),
                price,
                provenance,
                clock.instant());
        if (usage.isEmpty()) {
            usageRecorder.recordUnverifiable(turn.getWorkspaceId(), sample);
            return;
        }
        usageRecorder.record(turn.getWorkspaceId(), sample);
        BigDecimal cost = price.calculateCost(
                        usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens())
                .usd();
        if (recordOnTurn) {
            turns.recordCost(turn.getId(), cost);
        }
        if (cost != null) {
            DistributionSummary.builder(AgentMetrics.MENTOR_TURN_COST)
                    .baseUnit("usd")
                    .register(meters)
                    .record(cost.doubleValue());
        }
    }
}
