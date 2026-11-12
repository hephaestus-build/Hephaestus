package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.mentor.sandbox.TurnAttempt;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnProgress;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.NamedInterface;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Ends a turn's attempts: records the cost of each attempt's ended model calls when it ends or its lease expires, and
 * settles the conversational feedback Hephaestus's answer showed
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Delivery of shown feedback).
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@NamedInterface(name = "mentor-turn")
public class MentorTurnCompletion {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnCompletion.class);

    public static final String COULD_NOT_START = "Hephaestus could not start. Try again in a moment.";

    public static final String TOOK_TOO_LONG = "Hephaestus took too long to reply and stopped. Try again.";

    static final String NOT_SHOWN = "Hephaestus's answer did not show the feedback.";
    static final String CUT_OFF = "Hephaestus's answer stopped or failed after it showed the feedback.";
    static final String LEASE_LOST = "Hephaestus's answer was lost while it showed the feedback.";
    static final String SILENCED = "Silent Mode kept Hephaestus's answer from Slack.";
    static final String SLACK_UNCERTAIN = "Slack did not confirm what Hephaestus's answer showed.";
    static final String SLACK_PARTLY = "Slack showed only part of what Hephaestus's answer showed of the feedback.";

    private final MentorTurnService turns;
    private final ConversationClaims conversationClaims;
    private final LlmUsageEventRepository usage;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Sends the host's newest partial answer to readers; no row stores it.
     *
     * @param startedAt when the attempt started, for its first partial answer, which times the attempt's first token;
     *     {@code null} for every later one
     */
    public boolean progress(
            TurnAttempt turn, MentorTurnProgress.Update update, @Nullable Instant startedAt, boolean cold) {
        boolean sent = turns.updatePartial(turn.workspaceId(), turn.turnId(), turn.attempt(), turn.runId(), update);
        if (sent && startedAt != null) {
            firstToken(meters, cold).record(Duration.between(startedAt, clock.instant()));
        }
        return sent;
    }

    /**
     * How an attempt ended: as its host reported, or as the server decided when the host could not report.
     *
     * @param entries every Pi entry the attempt appended; none when the server ends it
     * @param error what the developer reads about a failed turn
     * @param cause what the operator reads about it in the server log and on the turn: the host's reason and kind of
     *     failure, or why the server ended the attempt itself. It is logged and stored as given, so it names closed
     *     kinds and classes, never a host's, a provider's or an exception's text.
     * @param lastFailedCall the attempt's last model call that failed, as the server's LLM proxy saw it; {@code null}
     *     when none did
     * @param lastPartial the partial answer this server sent last, which readers may have seen; {@code null} before
     *     the first
     */
    public record Ending(
            MentorTurn.Status outcome,
            List<JsonNode> entries,
            @Nullable String error,
            @Nullable String cause,
            MentorTurn.@Nullable FailedCall lastFailedCall,
            @Nullable JsonNode lastPartial) {}

    /**
     * Ends the attempt, bills it, and records and logs why a failed attempt failed. With the end, it records the
     * receipts the feedback the attempt claimed is due, except for a Slack answer that ended {@code DONE}: what Slack
     * shows settles that one.
     */
    @Transactional
    public MentorTurn end(long workspaceId, UUID turnId, int epoch, UUID runId, Ending ending) {
        return finished(
                turns.end(
                        workspaceId,
                        turnId,
                        epoch,
                        runId,
                        new MentorTurnService.Ending(
                                ending.outcome(), ending.entries(), ending.lastPartial(), ending.error())),
                epoch,
                ending);
    }

    /**
     * Ends, for the server, the attempt of a locked turn whose run's holder did not end it, as {@link #end} does for the
     * holder; nothing the holder wrote is stored ({@link MentorTurnService#endWithoutHolder}). The caller ends the run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public MentorTurn endWithoutHolder(MentorTurn locked, Ending ending) {
        int attempt = locked.getAttempt();
        return finished(
                turns.endWithoutHolder(locked, ending.outcome(), ending.error(), ending.lastPartial()),
                attempt,
                ending);
    }

    private MentorTurn finished(MentorTurnService.EndedTurn ended, int attempt, Ending ending) {
        MentorTurn turn = ended.turn();
        long workspaceId = turn.getWorkspaceId();
        UUID turnId = turn.getId();
        String status = turn.getStatus().name().toLowerCase(Locale.ROOT);
        TransactionCallbacks.afterCommit(() -> log.atInfo()
                .addKeyValue("event.name", "mentor.turn.ended")
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, workspaceId)
                .addKeyValue("turn.id", turnId)
                .addKeyValue("turn.attempt", attempt)
                .addKeyValue("turn.outcome", status)
                .log("Conversation turn ended"));
        if (turn.getStatus() == MentorTurn.Status.FAILED) {
            String failureCause = ending.cause() == null ? turn.getFailureCause() : ending.cause();
            turns.recordFailure(turn, failureCause, ending.lastFailedCall());
            // After commit: a rolled-back end is repeated by its host and would log the failure twice.
            String cause = failureCause == null ? turn.getError() : failureCause;
            MentorTurn.FailedCall lastFailedCall = ending.lastFailedCall();
            TransactionCallbacks.afterCommit(() -> log.warn(
                    "conversation turn {} attempt {} failed: {}; last failed model call: {}",
                    turnId,
                    attempt,
                    cause,
                    lastFailedCall == null ? "none" : lastFailedCall.describe()));
        }
        bill(turn, attempt);
        if (turn.getSurface() == MentorTurn.Surface.APP || turn.getStatus() != MentorTurn.Status.DONE) {
            // The app shows what a DONE answer showed; a stopped or failed answer may have shown what it called.
            receiptsDue(
                    turn,
                    turn.getStatus() == MentorTurn.Status.DONE ? null : Set.of(),
                    ended.feedbackCalled(),
                    ConversationCopyOutcome.FAILED,
                    CUT_OFF,
                    NOT_SHOWN);
        }
        completed(
                switch (turn.getStatus()) {
                    case STOPPED -> "stopped";
                    case FAILED -> "failed";
                    default -> "success";
                });
        Instant started = turn.getStartedAt();
        Instant finished = turn.getFinishedAt();
        if (started != null && finished != null) {
            duration(meters).record(Duration.between(started, finished));
        }
        return turn;
    }

    /**
     * Records, in the caller's transaction, the receipts of the feedback a Slack answer claimed by what reached the
     * developer: the feedback whose words Slack shows is delivered, feedback Slack shows only in part stays
     * unconfirmed, and any other was not shown.
     *
     * @param shownInSlack the feedback whose every word the Slack reply shows
     * @param partlyShownInSlack the feedback of which the Slack reply shows some words but not all
     */
    @Transactional
    public void recordDelivery(
            long workspaceId, UUID turnId, Collection<UUID> shownInSlack, Collection<UUID> partlyShownInSlack) {
        receiptsDue(
                turns.require(workspaceId, turnId),
                Set.copyOf(shownInSlack),
                Set.copyOf(partlyShownInSlack),
                ConversationCopyOutcome.FAILED,
                SLACK_PARTLY,
                NOT_SHOWN);
    }

    /** Leaves the feedback a Slack answer claimed unconfirmed when Slack cannot tell what the answer showed. */
    @Transactional
    public void recordUncertain(long workspaceId, UUID turnId) {
        receiptsDue(
                turns.require(workspaceId, turnId),
                Set.of(),
                Set.of(),
                ConversationCopyOutcome.UNCONFIRMED,
                SLACK_UNCERTAIN,
                NOT_SHOWN);
    }

    /**
     * Settles the feedback a Slack answer claimed when Silent Mode kept the rest of the answer from leaving the
     * instance: what a stream already showed counts as {@link #recordDelivery} counts it, and the rest failed.
     */
    @Transactional
    public void recordSilenced(
            long workspaceId, UUID turnId, Collection<UUID> shownInSlack, Collection<UUID> partlyShownInSlack) {
        receiptsDue(
                turns.require(workspaceId, turnId),
                Set.copyOf(shownInSlack),
                Set.copyOf(partlyShownInSlack),
                ConversationCopyOutcome.FAILED,
                SLACK_PARTLY,
                SILENCED);
    }

    /**
     * Bills an attempt whose run lost its holder ({@link MentorTurnService#takeBack(MentorTurn, boolean)}) or that was
     * taken back. With it, the
     * feedback it claimed is due an unconfirmed receipt: its host may have shown the words, and the next attempt's
     * brief does not list it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void billLostAttempt(MentorTurnService.LostAttempt lost) {
        billLost(lost);
    }

    private void billLost(MentorTurnService.LostAttempt lost) {
        MentorTurn turn = lost.turn();
        long workspaceId = turn.getWorkspaceId();
        UUID turnId = turn.getId();
        events.publishEvent(new ConversationReceiptsDue(
                workspaceId,
                turnId,
                lost.startedAt(),
                lost.lostAt(),
                Set.of(),
                Set.of(),
                ConversationCopyOutcome.UNCONFIRMED,
                LEASE_LOST,
                NOT_SHOWN));
        bill(turn, lost.attempt());
        if (turn.getStatus() == MentorTurn.Status.QUEUED) {
            meters.counter(AgentMetrics.MENTOR_TURN_RETRIED).increment();
        }
        completed("lease_expired");
    }

    /** Bills an attempt that the ledger took back ({@link MentorTurnService#requeueRunning}). */
    @ApplicationModuleListener
    public void billTakenBack(MentorLedgerEvents.AttemptTakenBack taken) {
        billLost(new MentorTurnService.LostAttempt(
                turns.require(taken.workspaceId(), taken.turnId()),
                taken.attempt(),
                taken.admission(),
                taken.startedAt(),
                taken.lostAt()));
    }

    private void completed(String outcome) {
        meters.counter(AgentMetrics.MENTOR_TURN_COMPLETED, "outcome", outcome).increment();
    }

    /** Records, in the outcome's transaction, the receipts the claims of the turn's last attempt are due. */
    private void receiptsDue(
            MentorTurn turn,
            @Nullable Set<UUID> shown,
            Set<UUID> unconfirmed,
            ConversationCopyOutcome otherwise,
            String unconfirmedReason,
            String failedReason) {
        Instant attemptStart = turn.getStartedAt();
        if (attemptStart == null) {
            return;
        }
        events.publishEvent(new ConversationReceiptsDue(
                turn.getWorkspaceId(),
                turn.getId(),
                attemptStart,
                turns.databaseNow(),
                shown,
                unconfirmed,
                otherwise,
                unconfirmedReason,
                failedReason));
    }

    /**
     * Records a receipt for each conversation copy that the attempt claimed and that has none. A delivered copy names
     * the message whose {@code show_feedback} call showed it. A receipt is stored once, so a repeat settles only what
     * is left. A store that refuses a receipt for good leaves its copy claimed, which the feedback shows as
     * unconfirmed; any other failure is thrown, so the publication registry runs this again.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void settle(ConversationReceiptsDue due) {
        long workspaceId = due.workspaceId();
        UUID turnId = due.turnId();
        var claims =
                conversationClaims.unsettledConversationClaims(workspaceId, turnId, due.attemptStart(), due.until());
        if (claims.isEmpty()) {
            return;
        }
        Map<UUID, UUID> shownIn = turns.answer(workspaceId, turnId).shownIn();
        Instant now = turns.databaseNow();
        RuntimeException retry = null;
        for (CopyWriteFence claim : claims) {
            ConversationCopyOutcome settled = due.outcome(claim.feedbackId(), shownIn.containsKey(claim.feedbackId()));
            ConversationCopyReceipt receipt = new ConversationCopyReceipt(
                    turnId,
                    settled == ConversationCopyOutcome.DELIVERED ? shownIn.get(claim.feedbackId()) : null,
                    settled,
                    now,
                    switch (settled) {
                        case DELIVERED -> null;
                        case UNCONFIRMED -> due.unconfirmedReason();
                        case FAILED -> due.failedReason();
                    });
            try {
                conversationClaims.recordConversationReceipt(workspaceId, claim, receipt);
            } catch (ResponseStatusException | IllegalArgumentException refused) {
                // The status or the class: the refusal's text can quote the feedback.
                log.warn(
                        "Conversation copy receipt refused: workspaceId={}, turnId={}, feedbackId={}, refusal={}",
                        workspaceId,
                        turnId,
                        claim.feedbackId(),
                        refused instanceof ResponseStatusException status
                                ? status.getStatusCode()
                                : refused.getClass().getSimpleName());
            } catch (RuntimeException failure) {
                log.warn(
                        "Conversation copy receipt could not be stored; it is tried again: workspaceId={}, turnId={},"
                                + " failure={}",
                        workspaceId,
                        turnId,
                        failure.getClass().getSimpleName());
                retry = failure;
            }
        }
        if (retry != null) {
            throw retry;
        }
    }

    /**
     * Records what the attempt's calls cost, from their ledger rows: the calls that ended by now. An attempt that made no
     * call, or a call without a price, records nothing.
     */
    private void bill(MentorTurn turn, int attempt) {
        BigDecimal cost =
                usage.sumAttemptCost(turn.getWorkspaceId(), UsageSourceType.MENTOR_TURN.name(), turn.getId(), attempt);
        if (cost == null) return;
        DistributionSummary.builder(AgentMetrics.MENTOR_TURN_COST)
                .baseUnit("usd")
                .register(meters)
                .record(cost.doubleValue());
    }

    /** Registers both timers at zero, so {@code increase()} also counts the first turn after a start. */
    @PostConstruct
    void registerTimers() {
        firstToken(meters, true);
        firstToken(meters, false);
        duration(meters);
    }

    /** The conversation objective's 10 seconds, with room above it. */
    private static Timer firstToken(MeterRegistry meters, boolean cold) {
        return Timer.builder(AgentMetrics.AGENT_RUN_FIRST_TOKEN)
                .tags("kind", "turn", "start", cold ? "cold" : "warm")
                .serviceLevelObjectives(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60))
                .register(meters);
    }

    /** An attempt can run for minutes; a percentile histogram stops at 30 seconds. */
    private static Timer duration(MeterRegistry meters) {
        return Timer.builder(AgentMetrics.MENTOR_TURN_DURATION)
                .serviceLevelObjectives(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(10))
                .register(meters);
    }
}
