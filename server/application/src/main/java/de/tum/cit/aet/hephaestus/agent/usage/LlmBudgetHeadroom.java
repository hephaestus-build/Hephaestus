package de.tum.cit.aet.hephaestus.agent.usage;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's two caps and what the ledger says has been spent against each, kept as numbers so the budget alert
 * can tell the call that crossed a cap from the calls after it. The spend counts each open or unmeasured call at its
 * upper bound.
 *
 * <p>A {@code null} budget is an uncapped purse: never blocked, and its spend is never even queried,
 * so the matching {@code spent} field is {@code null} too.
 *
 * <p>{@code hasUnpricedSpend} may read {@code false} on a purse that is already exhausted on ledger
 * spend alone, because the producer skips the probe there. Harmless: EXHAUSTED outranks UNVERIFIABLE.
 */
public record LlmBudgetHeadroom(
        @Nullable BigDecimal instanceSpentUsd,
        @Nullable BigDecimal instanceBudgetUsd,
        boolean instanceHasUnpricedSpend,
        @Nullable BigDecimal workspaceSpentUsd,
        @Nullable BigDecimal workspaceBudgetUsd,
        boolean workspaceHasUnpricedSpend) {
    public static final LlmBudgetHeadroom UNCAPPED = new LlmBudgetHeadroom(null, null, false, null, null, false);

    /** The verdict on the ledger's spend. */
    public LlmBudgetDecision decide() {
        return new LlmBudgetDecision(
                reason(instanceSpentUsd, instanceBudgetUsd, instanceHasUnpricedSpend),
                reason(workspaceSpentUsd, workspaceBudgetUsd, workspaceHasUnpricedSpend));
    }

    /**
     * Whether a cap applies to a call that this purse pays.
     *
     * @param purse who pays; {@code null} means unattributable, and such a call is judged against both caps
     */
    public boolean cappedFor(@Nullable UsageFundingSource purse) {
        boolean instance = instanceBudgetUsd != null && purse != UsageFundingSource.WORKSPACE;
        boolean workspace = workspaceBudgetUsd != null && purse != UsageFundingSource.INSTANCE;
        return instance || workspace;
    }

    /**
     * Whether a call that can cost at most {@code boundUsd} must wait for the next month: a capped purse that pays for it
     * has reached its cap, or would pass it once the call's bound is held.
     *
     * @param purse who pays; {@code null} means unattributable, and such a call is held against both caps
     */
    public boolean refusesHold(@Nullable UsageFundingSource purse, BigDecimal boundUsd) {
        return (purse != UsageFundingSource.WORKSPACE && passes(instanceSpentUsd, instanceBudgetUsd, boundUsd))
                || (purse != UsageFundingSource.INSTANCE && passes(workspaceSpentUsd, workspaceBudgetUsd, boundUsd));
    }

    private static boolean passes(@Nullable BigDecimal spentUsd, @Nullable BigDecimal budgetUsd, BigDecimal boundUsd) {
        return spentUsd != null
                && budgetUsd != null
                && (LlmBudgetService.capReached(spentUsd, budgetUsd)
                        || spentUsd.add(boundUsd).compareTo(budgetUsd) > 0);
    }

    private static LlmBudgetBlockReason reason(
            @Nullable BigDecimal spentUsd, @Nullable BigDecimal budgetUsd, boolean hasUnpricedSpend) {
        if (budgetUsd == null || spentUsd == null) {
            return LlmBudgetBlockReason.NONE;
        }
        if (LlmBudgetService.capReached(spentUsd, budgetUsd)) {
            return LlmBudgetBlockReason.EXHAUSTED;
        }
        return hasUnpricedSpend ? LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED : LlmBudgetBlockReason.NONE;
    }
}
