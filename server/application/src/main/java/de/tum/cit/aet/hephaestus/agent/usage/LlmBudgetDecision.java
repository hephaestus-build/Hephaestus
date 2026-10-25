package de.tum.cit.aet.hephaestus.agent.usage;

import org.jspecify.annotations.Nullable;

/**
 * Whether a workspace may still spend, decided separately for each funding source. "Is this workspace
 * blocked?" has no single answer — only "is THIS call blocked?", which depends on who is paying.
 */
public record LlmBudgetDecision(LlmBudgetBlockReason instanceFunded, LlmBudgetBlockReason workspaceFunded) {
    public static final LlmBudgetDecision ALLOWED =
            new LlmBudgetDecision(LlmBudgetBlockReason.NONE, LlmBudgetBlockReason.NONE);

    /**
     * @param purse the purse that produced the block, or {@code null} when nothing is blocked. For an
     *     unattributable call this is the purse actually consulted, which is not always the one the
     *     caller asked about — log lines and metric tags must read it here.
     */
    public record Block(@Nullable UsageFundingSource purse, LlmBudgetBlockReason reason) {
        static final Block NONE = new Block(null, LlmBudgetBlockReason.NONE);

        public boolean blocked() {
            return reason != LlmBudgetBlockReason.NONE;
        }
    }

    /**
     * An unknown funding source is judged by BOTH caps — it might be either purse, so it may only
     * proceed when neither is blocked. Fail-safe: an unattributable call is not a way around a cap.
     */
    public Block decideFor(@Nullable UsageFundingSource fundingSource) {
        if (fundingSource == null) {
            if (instanceFunded != LlmBudgetBlockReason.NONE) {
                return new Block(UsageFundingSource.INSTANCE, instanceFunded);
            }
            return workspaceFunded != LlmBudgetBlockReason.NONE
                    ? new Block(UsageFundingSource.WORKSPACE, workspaceFunded)
                    : Block.NONE;
        }
        LlmBudgetBlockReason reason = switch (fundingSource) {
            case INSTANCE -> instanceFunded;
            case WORKSPACE -> workspaceFunded;
        };
        return reason != LlmBudgetBlockReason.NONE ? new Block(fundingSource, reason) : Block.NONE;
    }

    public LlmBudgetBlockReason forFunding(@Nullable UsageFundingSource fundingSource) {
        return decideFor(fundingSource).reason();
    }

    public boolean blocks(@Nullable UsageFundingSource fundingSource) {
        return decideFor(fundingSource).blocked();
    }
}
