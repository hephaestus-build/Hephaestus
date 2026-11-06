package de.tum.cit.aet.hephaestus.agent.usage;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * A workspace's two caps and what the ledger says has been spent against each, kept as numbers so a
 * caller that can see spend the ledger cannot yet — the LLM proxy, for work that still runs — can add it
 * before the comparison rather than after the money is gone.
 *
 * <p>A {@code null} budget is an uncapped purse: never blocked, and its spend is never even queried,
 * so the matching {@code spent} field is {@code null} too.
 *
 * <p>{@code hasUnpricedSpend} may read {@code false} on a purse that is already exhausted on ledger
 * spend alone, because the producer skips the probe there. Harmless: running spend is never negative,
 * so such a purse stays exhausted, and EXHAUSTED outranks UNVERIFIABLE.
 */
public record LlmBudgetHeadroom(
        @Nullable BigDecimal instanceSpentUsd,
        @Nullable BigDecimal instanceBudgetUsd,
        boolean instanceHasUnpricedSpend,
        @Nullable BigDecimal workspaceSpentUsd,
        @Nullable BigDecimal workspaceBudgetUsd,
        boolean workspaceHasUnpricedSpend) {
    public static final LlmBudgetHeadroom UNCAPPED = new LlmBudgetHeadroom(null, null, false, null, null, false);

    /** The verdict on recorded spend alone. */
    public LlmBudgetDecision decide() {
        return decideWith(RunningSpend.NONE);
    }

    /**
     * Whether a cap applies to a call that this purse pays, which is the only case in which running spend can change its
     * verdict.
     *
     * @param purse who pays; {@code null} means unattributable, and such a call is judged against both caps
     */
    public boolean cappedFor(@Nullable UsageFundingSource purse) {
        boolean instance = instanceBudgetUsd != null && purse != UsageFundingSource.WORKSPACE;
        boolean workspace = workspaceBudgetUsd != null && purse != UsageFundingSource.INSTANCE;
        return instance || workspace;
    }

    /** The verdict once the spend of the work that runs now is charged to its purses. */
    public LlmBudgetDecision decideWith(RunningSpend running) {
        return new LlmBudgetDecision(
                reason(instanceSpentUsd, instanceBudgetUsd, instanceHasUnpricedSpend, running.instanceUsd()),
                reason(workspaceSpentUsd, workspaceBudgetUsd, workspaceHasUnpricedSpend, running.workspaceUsd()));
    }

    private static LlmBudgetBlockReason reason(
            @Nullable BigDecimal spentUsd,
            @Nullable BigDecimal budgetUsd,
            boolean hasUnpricedSpend,
            BigDecimal runningUsd) {
        if (budgetUsd == null || spentUsd == null) {
            return LlmBudgetBlockReason.NONE;
        }
        if (LlmBudgetService.capReached(spentUsd.add(runningUsd), budgetUsd)) {
            return LlmBudgetBlockReason.EXHAUSTED;
        }
        return hasUnpricedSpend ? LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED : LlmBudgetBlockReason.NONE;
    }
}
