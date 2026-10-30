package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder.LlmUsageSample;
import de.tum.cit.aet.hephaestus.agent.usage.MeasurementVerification;
import de.tum.cit.aet.hephaestus.agent.usage.UsageProvenance;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorLeaseLostException;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the measurement of each conversation turn attempt ({@link MentorAttemptUsage}) and its ledger row, which is its
 * projection. Admission creates the measurement, under the account's person-data write fence, before the proxy can
 * route a call; the proxy adds each returned call; the transaction that ends the attempt, or takes back its lease,
 * dates it ({@link #attemptEnded}); billing then projects it at that date; an erasure removes it. Each path takes the
 * turn row first and the measurement second. Neither a returned call nor a later billing chooses the date.
 *
 * <p>A call that returns after its attempt ended, or after the turn started its next attempt, is added to its own
 * attempt only, and that attempt's ledger row is projected again through {@link LlmUsageRecorder#project}: the same
 * row, its frozen price, date and any price the repricing pass found. It never changes the turn's status, answer,
 * feedback or lease, nor the counters of the attempt that runs now. A call whose attempt has no measurement is not
 * counted, and nothing here creates one for it.
 *
 * <p>Only the proxy measures a conversation turn, so the projection is the proxy's own total: verified when it has tokens,
 * unverifiable when it has calls without any. It covers the calls the proxy observed with reported usage, not every
 * call a provider bills.
 */
@Service
@RequiredArgsConstructor
public class MentorAttemptUsageService {

    private static final Logger log = LoggerFactory.getLogger(MentorAttemptUsageService.class);

    private final MentorTurnService turns;
    private final MentorTurnRepository turnRows;
    private final MentorAttemptUsageRepository attempts;
    private final LlmUsageRecorder recorder;
    private final PersonDataWriteFence writeFence;
    private final Clock clock;

    /** Where a returned call was settled. */
    public enum CallSettlement {
        /** On the attempt that still holds its lease; the turn's in-flight counters took it too. */
        CURRENT,
        /** On an attempt that already ended or was taken back; its ledger row was projected again. */
        LATE,
        /**
         * Kept on an attempt that no longer runs but has no end date: the call is in the measurement, and the
         * attempt's end projects it. A returned call never dates an attempt.
         */
        RETAINED,
        /** Dropped: the turn no longer exists. */
        OWNER_GONE,
        /** Dropped: the attempt has no measurement, because it was admitted before it existed or was erased. */
        UNREGISTERED
    }

    /**
     * Freezes the model and rates the attempt runs with, and creates its empty measurement, in one transaction. The
     * proxy routes no call of an attempt before its admission, so every call it routes has a measurement to land on.
     * Like the admission alone, it throws when this worker no longer holds the attempt, and then creates nothing.
     *
     * <p>Takes the person-data write fence of the turn's account first, before any row lock, as the end of an attempt
     * does. An erasure holds that fence exclusively while it suppresses the account, so a registration either commits
     * before the suppression, and the erasure's preparation removes its measurement, or it starts after it and is
     * refused here.
     *
     * @return false when the account's person data may not be written, because an erasure suppressed it; nothing is
     *     admitted then
     */
    @Transactional
    public boolean admit(UUID turnId, int attempt, String workerId, MentorTurnAdmission admitted) {
        MentorTurn found = turnRows.findById(turnId).orElseThrow(() -> new MentorLeaseLostException(turnId, attempt));
        long workspaceId = found.getWorkspaceId();
        boolean writable = turnRows.findAccountIdByIdAndWorkspaceId(turnId, workspaceId)
                .map(writeFence::holdForAccountWrite)
                .orElse(false);
        if (!writable) return false;
        turns.admit(turnId, attempt, workerId, admitted.toJson());
        attempts.register(turnId, attempt, workspaceId, admitted.model(), admitted.price());
        return true;
    }

    /**
     * Dates the attempt's spend with its end, in the transaction that ends it or takes it back, before a requeue resets
     * the turn. The first end wins; an attempt that made no call is dated too, because a late call may still reach
     * it. An attempt without a measurement is left as it is.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void attemptEnded(MentorLedgerEvents.AttemptEnded ended) {
        attempts.dateOnce(ended.workspaceId(), ended.turnId(), ended.attempt(), ended.endedAt());
    }

    /**
     * Adds one returned call to the attempt that authenticated it, in the caller's transaction. The attempt that still
     * holds its lease also takes it on the turn's counters, which the proxy's in-flight budget reads.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CallSettlement settleCall(UUID turnId, int attempt, MentorTurnUsage call) {
        MentorTurn turn = turnRows.lockById(turnId).orElse(null);
        if (turn == null) return CallSettlement.OWNER_GONE;
        MentorAttemptUsage measured =
                attempts.lock(turn.getWorkspaceId(), turnId, attempt).orElse(null);
        if (measured == null) return CallSettlement.UNREGISTERED;
        measured.add(call);
        if (turn.getAttempt() == attempt && turnRows.accumulateUsage(turnId, attempt, call) == 1) {
            return CallSettlement.CURRENT;
        }
        if (!measured.isDated()) return CallSettlement.RETAINED;
        project(turn, measured, turn.getAttempt() == attempt && turn.getStatus().isTerminal());
        return CallSettlement.LATE;
    }

    /**
     * Settles the end of an attempt in the caller's transaction: projects its ledger row at the date its end gave it
     * ({@link #attemptEnded}). This runs in the end's transaction, or later for a taken-back attempt; it never dates
     * the attempt itself. Repeating it projects the same row again.
     *
     * @param recordOnTurn whether the turn still shows this attempt, so its cost is recorded on the turn
     * @return the attempt's cost, empty when it made no priced call, has no measurement or no end date
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> settle(UUID turnId, int attempt, boolean recordOnTurn) {
        MentorTurn turn = turnRows.lockById(turnId).orElse(null);
        if (turn == null) return Optional.empty();
        MentorAttemptUsage measured =
                attempts.lock(turn.getWorkspaceId(), turnId, attempt).orElse(null);
        if (measured == null) {
            log.warn("conversation turn {} attempt {} has no usage measurement; it is not billed", turnId, attempt);
            return Optional.empty();
        }
        if (!measured.isDated()) {
            log.warn("conversation turn {} attempt {} has no end date; it is not billed yet", turnId, attempt);
            return Optional.empty();
        }
        return project(turn, measured, recordOnTurn);
    }

    /**
     * Removes every measurement of a turn that an erasure selected, before any store erases. The spend observed so far
     * stays in the ledger: every measurement is projected first, at its own date, also one whose attempt ended but
     * whose billing has not run yet; projecting an already billed attempt again changes nothing. Then the turn's ledger
     * rows are detached with the ledger store's own redaction, so a row written after the erasure's preview is
     * detached too. A call that returns afterwards finds no measurement and is dropped; nothing writes the turn back
     * into the ledger.
     */
    @Transactional
    public void forgetTurn(long workspaceId, UUID turnId) {
        MentorTurn turn = turnRows.lockByIdAndWorkspaceId(turnId, workspaceId).orElse(null);
        if (turn == null) return;
        for (MentorAttemptUsage measured : attempts.lockAttempts(workspaceId, turnId)) {
            // An attempt that still runs ends its accounting here: the erasure is its last measured moment. An attempt
            // that ended keeps the date its end gave it.
            if (!measured.isDated()) measured.occurredAt(clock.instant());
            project(turn, measured, false);
        }
        attempts.deleteAttempts(workspaceId, turnId);
        attempts.detachLedger(workspaceId, turnId);
    }

    /**
     * Projects the attempt's whole measurement onto its one ledger row, at the date the attempt's end gave it. An
     * attempt that made no call has none.
     */
    private Optional<BigDecimal> project(MentorTurn turn, MentorAttemptUsage measured, boolean recordOnTurn) {
        int attempt = measured.getId().getAttempt();
        Instant occurredAt = Objects.requireNonNull(measured.getOccurredAt(), "Only a dated attempt is projected");
        MentorTurnUsage usage = measured.usage();
        if (usage.calls() == 0 && usage.isEmpty()) return Optional.empty();
        // Calls with no token on record: the row names no source rather than crediting one that saw nothing, and its
        // measurement is unverifiable.
        LlmPriceSnapshot price = measured.price();
        recorder.project(
                turn.getWorkspaceId(),
                new LlmUsageSample(
                        null,
                        UsageSourceType.MENTOR_TURN.name(),
                        UsageSourceType.MENTOR_TURN,
                        turn.getId(),
                        attempt,
                        measured.getModel(),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.cacheReadTokens(),
                        usage.cacheWriteTokens(),
                        usage.reasoningTokens(),
                        0L,
                        Math.max(1, usage.calls()),
                        price,
                        usage.isEmpty() ? UsageProvenance.NONE : UsageProvenance.PROXY,
                        usage.isEmpty() ? MeasurementVerification.UNVERIFIABLE : MeasurementVerification.VERIFIED,
                        occurredAt));
        if (usage.isEmpty()) return Optional.empty();
        BigDecimal cost = price.calculateCost(
                        usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens())
                .usd();
        if (recordOnTurn) turns.recordCost(turn.getId(), attempt, cost);
        return Optional.ofNullable(cost);
    }
}
