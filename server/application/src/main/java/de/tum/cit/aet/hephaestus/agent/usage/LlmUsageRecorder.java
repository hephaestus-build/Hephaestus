package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one writer of the LLM usage ledger: one row for each model call. The proxy opens the row before the call leaves
 * ({@link #open}) and measures it when the call ends ({@link #complete}). The end of the call's claim settles a row
 * that is still open ({@code AgentRuns}). The caller supplies the price frozen with the call; a measurement never
 * consults the mutable catalogue and never trusts a provider-reported model name for pricing.
 */
@Service
public class LlmUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageRecorder.class);

    private final LlmUsageEventRepository usageRepository;
    private final LlmBudgetService budgetService;
    private final MeterRegistry meterRegistry;

    public LlmUsageRecorder(
            LlmUsageEventRepository usageRepository, LlmBudgetService budgetService, MeterRegistry meterRegistry) {
        this.usageRepository = usageRepository;
        this.budgetService = budgetService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * A model call that its run claim or instance action makes now.
     *
     * @param workspaceId the workspace, or null for instance report drafts and instance model probes
     * @param leaseEpoch the claim of the run that makes the call, or null for instance work
     * @param sourceAttempt the job attempt or the turn attempt of the claim
     * @param practiceId the practice whose script makes the call; {@code null} for every other call
     * @param model the upstream model id that the call's configuration froze; {@code null} when none is readable
     * @param costBoundUsd the most that the call can cost at {@code price} ({@link LlmPriceSnapshot#upperBound});
     *     {@code null} when no bound is known
     */
    public record OpenCall(
            @Nullable Long workspaceId,
            @Nullable UUID runId,
            @Nullable Integer leaseEpoch,
            String jobType,
            UsageSourceType sourceType,
            UUID sourceId,
            int sourceAttempt,
            @Nullable UUID reviewId,
            ModelKind modelKind,
            @Nullable Long practiceId,
            @Nullable String model,
            LlmPriceSnapshot price,
            @Nullable BigDecimal costBoundUsd,
            @Nullable UUID actionId,
            @Nullable Instant settleAfter,
            @Nullable Long appliedInstanceModelId) {
        public OpenCall(
                @Nullable Long workspaceId,
                @Nullable UUID runId,
                @Nullable Integer leaseEpoch,
                String jobType,
                UsageSourceType sourceType,
                UUID sourceId,
                int sourceAttempt,
                @Nullable UUID reviewId,
                ModelKind modelKind,
                @Nullable Long practiceId,
                @Nullable String model,
                LlmPriceSnapshot price,
                @Nullable BigDecimal costBoundUsd,
                @Nullable UUID actionId,
                @Nullable Instant settleAfter) {
            this(
                    workspaceId,
                    runId,
                    leaseEpoch,
                    jobType,
                    sourceType,
                    sourceId,
                    sourceAttempt,
                    reviewId,
                    modelKind,
                    practiceId,
                    model,
                    price,
                    costBoundUsd,
                    actionId,
                    settleAfter,
                    null);
        }

        public OpenCall(
                @Nullable Long workspaceId,
                @Nullable UUID runId,
                @Nullable Integer leaseEpoch,
                String jobType,
                UsageSourceType sourceType,
                UUID sourceId,
                int sourceAttempt,
                @Nullable UUID reviewId,
                ModelKind modelKind,
                @Nullable Long practiceId,
                @Nullable String model,
                LlmPriceSnapshot price,
                @Nullable BigDecimal costBoundUsd) {
            this(
                    workspaceId,
                    runId,
                    leaseEpoch,
                    jobType,
                    sourceType,
                    sourceId,
                    sourceAttempt,
                    reviewId,
                    modelKind,
                    practiceId,
                    model,
                    price,
                    costBoundUsd,
                    null,
                    null);
        }
    }

    /**
     * What a provider reported for one call.
     *
     * @param inputTokens the prompt tokens that were neither cache reads nor cache writes
     * @param searchUnits what Cohere reranking calls bill instead of tokens; 0 for every other call
     */
    public record CallUsage(
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens,
            long searchUnits) {
        /** A call that its provider answered with an error: the provider bills nothing for it. */
        public static final CallUsage NONE = new CallUsage(0, 0, 0, 0, 0, 0);
    }

    /** The measurement that ends one call, including whether its original cost bound still holds. */
    public record Measurement(
            CallUsage usage,
            MeasurementVerification verification,
            UsagePricingState pricingState,
            @Nullable BigDecimal costUsd,
            boolean keepBound) {}

    /**
     * Writes the call's row {@link UsageCallState#OPEN}, dated now, with the frozen model and rates. The caller holds
     * the claim in the same transaction, so a claim that ended cannot open a call. Until the call is measured, the row
     * adds its upper bound to every spend total; a row without a bound costs zero on a priced model and is unknown on
     * an unpriced one.
     *
     * @return the call id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID open(OpenCall call) {
        UUID id = TimeOrderedIds.next();
        LlmPriceSnapshot price = call.price();
        usageRepository.insertOpen(
                id,
                call,
                price.pricingState() == UsagePricingState.UNPRICED
                        ? null
                        : BigDecimal.ZERO.setScale(LlmPriceSnapshot.LEDGER_SCALE));
        return id;
    }

    /** A runless call's durable deadline is measured from the same database clock that recovery compares. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Instant recoveryDeadline(Duration timeout) {
        return usageRepository.databaseClock().plus(timeout);
    }

    /**
     * Measures one call at its end, in the caller's transaction. A call without reported usage ends unverifiable and
     * unpriced, never at zero cost, and keeps its upper bound. A call whose claim ended first was settled unverifiable;
     * its measurement now replaces
     * the settlement at its frozen price, including a known free call. The source, the
     * model, the funding source, the rates and the date stay as the row froze them. A row that was measured already, or
     * that retention or a purge deleted, is left as it is.
     *
     * @return whether this call was measured now; a duplicate completion changes nothing
     * @param usage {@code null} when the provider reported no usage or the proxy could not read it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(UUID callId, @Nullable Long workspaceId, @Nullable CallUsage usage) {
        return completeCall(callId, workspaceId, usage, null);
    }

    /** Records the first failure independently of late usage or repricing, then measures the call. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(
            UUID callId,
            @Nullable Long workspaceId,
            @Nullable CallUsage usage,
            ConversationTurn.@Nullable FailedCall failure) {
        return completeCall(callId, workspaceId, usage, failure);
    }

    private boolean completeCall(
            UUID callId,
            @Nullable Long workspaceId,
            @Nullable CallUsage usage,
            ConversationTurn.@Nullable FailedCall failure) {
        LlmUsageEvent row = usageRepository.lockCall(callId, workspaceId).orElse(null);
        if (row == null) return false;
        if (failure != null) {
            var status = failure.status();
            // A non-standard provider status must not discard reported usage.
            usageRepository.recordFailure(
                    callId,
                    workspaceId,
                    failure.kind(),
                    status == null || status < 100 || status > 599 ? null : status);
        }
        boolean open = row.getCallState() == UsageCallState.OPEN;
        boolean settled = row.getCallState() == UsageCallState.SETTLED;
        if (!open && !settled) return false;
        if (usage == null) {
            if (!open) return false;
            usageRepository.measure(
                    callId,
                    workspaceId,
                    new Measurement(
                            CallUsage.NONE,
                            MeasurementVerification.UNVERIFIABLE,
                            UsagePricingState.UNPRICED,
                            null,
                            true));
            meterRegistry.counter(AgentMetrics.LLM_USAGE_UNVERIFIABLE).increment();
            meterRegistry.counter(AgentMetrics.LLM_USAGE_UNCOSTED).increment();
            return true;
        }
        LlmPriceSnapshot frozen = new LlmPriceSnapshot(
                row.getFundingSource(),
                row.getInitialPricingState(),
                row.getAppliedPriceId(),
                row.getAppliedWorkspaceModelId(),
                row.getAppliedPer1mInputUsd(),
                row.getAppliedPer1mOutputUsd(),
                row.getAppliedPer1mCacheReadUsd(),
                row.getAppliedPer1mCacheWriteUsd());
        // A PRICED snapshot missing a rate would silently cost the tokens it does have a rate for.
        boolean rateMissing = isPricedWithARateMissing(usage, frozen);
        LlmPriceSnapshot price = rateMissing ? withPricingState(frozen, UsagePricingState.UNPRICED) : frozen;
        // A rejected call or a verified zero-unit answer needs no rate. Missing usage took the branch above and
        // remains unknown; it must never acquire this zero charge.
        if (usage.equals(CallUsage.NONE) && frozen.pricingState() == UsagePricingState.UNPRICED)
            price = withPricingState(frozen, UsagePricingState.NO_CHARGE);
        LlmPriceSnapshot.Cost computed = price.calculateCost(
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens());
        reportClamp(callId, computed);
        usageRepository.measure(
                callId,
                workspaceId,
                new Measurement(
                        usage,
                        MeasurementVerification.VERIFIED,
                        price.pricingState(),
                        price.pricingState() == UsagePricingState.UNPRICED ? null : computed.usd(),
                        // The bound assumed the rates that the call turned out to need; without one of them it bounds
                        // nothing.
                        !rateMissing));
        if (price.pricingState() == UsagePricingState.UNPRICED) {
            meterRegistry.counter(AgentMetrics.LLM_USAGE_UNCOSTED).increment();
        }
        if (open && workspaceId != null) registerBudgetAlert(workspaceId, price, computed.usd(), row.getOccurredAt());
        return true;
    }

    /** Ends the unmeasured calls owned by an action, in the transaction that ends it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleAction(UUID actionId) {
        reportSettled(usageRepository.settleAction(actionId));
    }

    /** Recovers instance calls after their deadline, lost claim or process failure. Late measurement stays possible. */
    @Transactional
    public void recoverInstanceCalls() {
        reportSettled(usageRepository.settleAbandonedInstanceCalls());
    }

    private void reportSettled(int count) {
        if (count == 0) return;
        meterRegistry.counter(AgentMetrics.LLM_USAGE_UNVERIFIABLE).increment(count);
        meterRegistry.counter(AgentMetrics.LLM_USAGE_UNCOSTED).increment(count);
    }

    /** Reads the failed call on any replica, scoped to the exact run claim that ends. */
    @Transactional(readOnly = true)
    public ConversationTurn.@Nullable FailedCall latestFailure(long workspaceId, UUID runId, int epoch) {
        return usageRepository.latestFailure(workspaceId, runId, epoch).orElse(null);
    }

    // Missing rates leave the whole call unpriced; search units require their own rate.
    private static boolean isPricedWithARateMissing(CallUsage usage, LlmPriceSnapshot price) {
        return (price.pricingState() == UsagePricingState.PRICED
                && ((usage.inputTokens() > 0 && price.per1mInputUsd() == null)
                        || (usage.outputTokens() > 0 && price.per1mOutputUsd() == null)
                        || (usage.cacheReadTokens() > 0 && price.per1mCacheReadUsd() == null)
                        || (usage.cacheWriteTokens() > 0 && price.per1mCacheWriteUsd() == null)
                        || usage.searchUnits() > 0));
    }

    /** Keeps the frozen rates: they still say what the call would have cost. */
    private static LlmPriceSnapshot withPricingState(LlmPriceSnapshot price, UsagePricingState state) {
        return new LlmPriceSnapshot(
                price.fundingSource(),
                state,
                price.appliedPriceId(),
                price.appliedWorkspaceModelId(),
                price.per1mInputUsd(),
                price.per1mOutputUsd(),
                price.per1mCacheReadUsd(),
                price.per1mCacheWriteUsd());
    }

    /**
     * Emitted before the write, so the signal survives a failed write: a clamp is a fact about the price and the token
     * counts, not about whether the row landed.
     */
    private void reportClamp(UUID callId, LlmPriceSnapshot.Cost computed) {
        if (computed.clamp() == null) {
            return;
        }
        log.warn(
                "LLM cost clamped to fit the ledger column: clamp={}, storedUsd={}, callId={}",
                computed.clamp(),
                computed.usd(),
                callId);
        meterRegistry
                .counter(AgentMetrics.LLM_USAGE_COST_CLAMPED, "direction", clampTag(computed.clamp()))
                .increment();
    }

    private static String clampTag(LlmPriceSnapshot.CostClamp clamp) {
        return clamp == LlmPriceSnapshot.CostClamp.ROUNDED_UP_TO_MINIMUM ? "up_to_minimum" : "down_to_maximum";
    }

    private void registerBudgetAlert(
            Long workspaceId, LlmPriceSnapshot price, @Nullable BigDecimal cost, Instant occurredAt) {
        if (price.fundingSource() != UsageFundingSource.INSTANCE
                || price.pricingState() != UsagePricingState.PRICED
                || cost == null
                || cost.signum() <= 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("LLM usage must be recorded in a transaction");
        }
        // The cap compares the current UTC month; a call that started in an earlier month cannot cross it.
        if (!YearMonth.from(occurredAt.atZone(ZoneOffset.UTC)).equals(YearMonth.now(ZoneOffset.UTC))) return;
        TransactionCallbacks.afterCommit(() -> {
            try {
                alertIfBudgetCrossed(workspaceId, cost);
            } catch (RuntimeException e) {
                log.warn("Post-commit LLM budget alert failed for workspaceId={}", workspaceId, e);
                meterRegistry.counter(AgentMetrics.LLM_BUDGET_ALERT_FAILURE).increment();
            }
        });
    }

    /**
     * WARNs on the crossing, not the state, so a month that stays exhausted does not log on every subsequent call.
     * Compares measured spend only: an upper bound is not money spent, so the bound of an open call cannot raise the
     * alert, and the call that measures the crossing raises it once.
     */
    private void alertIfBudgetCrossed(Long workspaceId, BigDecimal callCost) {
        LlmBudgetService.MeasuredSpend measured = budgetService.measuredInstanceSpend(workspaceId);
        if (measured == null) {
            return;
        }
        BigDecimal monthToDate = measured.spentUsd();
        BigDecimal budget = measured.budgetUsd();
        boolean crossedNow = LlmBudgetService.capReached(monthToDate, budget)
                && !LlmBudgetService.capReached(monthToDate.subtract(callCost), budget);
        if (crossedNow) {
            log.warn(
                    "Workspace LLM budget exhausted: workspaceId={}, budgetUsd={}, monthToDateUsd={}",
                    workspaceId,
                    budget,
                    monthToDate);
            meterRegistry.counter(AgentMetrics.LLM_BUDGET_EXHAUSTED).increment();
        }
    }
}
