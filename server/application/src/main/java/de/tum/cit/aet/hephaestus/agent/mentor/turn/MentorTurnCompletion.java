package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStore;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
 * Ends a turn's attempts: bills each attempt when it ends or its lease expires, from the attempt's usage measurement
 * ({@link MentorAttemptUsageService}), and settles the conversational feedback Hephaestus's answer showed
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Delivery of shown feedback).
 */
@Service
@ConditionalOnCaptureRole
@RequiredArgsConstructor
@NamedInterface(name = "mentor-turn")
public class MentorTurnCompletion {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnCompletion.class);

    public static final String COULD_NOT_START = "Hephaestus could not start. Try again in a moment.";

    static final String NOT_SHOWN = "Hephaestus's answer did not show the feedback.";
    static final String CUT_OFF = "Hephaestus's answer stopped or failed after it showed the feedback.";
    static final String LEASE_LOST = "Hephaestus's answer was lost while it showed the feedback.";
    static final String SILENCED = "Silent Mode kept Hephaestus's answer from Slack.";
    static final String SLACK_UNCERTAIN = "Slack did not confirm what Hephaestus's answer showed.";
    static final String SLACK_PARTLY = "Slack showed only part of what Hephaestus's answer showed of the feedback.";

    private final MentorTurnService turns;
    private final FeedbackStore feedback;
    private final ConversationClaims conversationClaims;
    private final MentorAttemptUsageService attemptUsage;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Sends the host's newest partial answer to readers; no row stores it.
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
     * @param cause what the operator reads about it in the worker log and on the turn: the host's reason and kind of
     *     failure, or why the worker ended the attempt itself. It is logged and stored as given, so it names closed
     *     kinds and classes, never a host's, a provider's or an exception's text.
     * @param lastFailedCall the attempt's last model call that failed, as the worker's LLM proxy saw it; {@code null}
     *     when none did
     * @param lastPartial the partial answer this worker sent last, which readers may have seen; {@code null} before
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
    public MentorTurn end(UUID turnId, int attempt, String workerId, Ending ending) {
        MentorTurnService.EndedTurn ended = turns.end(
                turnId,
                attempt,
                workerId,
                new MentorTurnService.AttemptEnd(
                        ending.outcome(), ending.entries(), ending.lastPartial(), ending.error()));
        MentorTurn turn = ended.turn();
        if (turn.getStatus() == MentorTurn.Status.FAILED) {
            turns.recordFailure(turn, ending.cause(), ending.lastFailedCall());
            // After commit: a rolled-back end is repeated by its host and would log the failure twice.
            String cause = ending.cause() == null ? ending.error() : ending.cause();
            MentorTurn.FailedCall lastFailedCall = ending.lastFailedCall();
            TransactionCallbacks.afterCommit(() -> log.warn(
                    "conversation turn {} attempt {} failed: {}; last failed model call: {}",
                    turnId,
                    attempt,
                    cause,
                    lastFailedCall == null ? "none" : lastFailedCall.describe()));
        }
        bill(turn, attempt, true);
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
            Timer.builder(AgentMetrics.MENTOR_TURN_DURATION)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(Duration.between(started, finished));
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
     * Bills an attempt whose lease expired ({@link MentorTurnService#expireLease}) or that was taken back. With it, the
     * feedback it claimed is due an unconfirmed receipt: its host may have shown the words, and the next attempt's
     * brief does not list it.
     */
    @Transactional
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
        bill(turn, lost.attempt(), turn.getStatus().isTerminal());
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
                taken.proxyUsage(),
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
                feedback.recordConversationReceipt(workspaceId, claim, receipt);
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
     * Settles the attempt's spend from its usage measurement ({@link MentorAttemptUsageService#settle}), which every
     * proxied call of the attempt reached, also one that returned after this. An attempt that made no call is not
     * billed.
     */
    private void bill(MentorTurn turn, int attempt, boolean recordOnTurn) {
        attemptUsage
                .settle(turn.getId(), attempt, recordOnTurn)
                .ifPresent(cost -> DistributionSummary.builder(AgentMetrics.MENTOR_TURN_COST)
                        .baseUnit("usd")
                        .register(meters)
                        .record(cost.doubleValue()));
    }
}
