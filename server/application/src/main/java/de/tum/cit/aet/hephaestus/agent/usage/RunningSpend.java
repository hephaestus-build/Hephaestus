package de.tum.cit.aet.hephaestus.agent.usage;

import java.math.BigDecimal;

/**
 * What the reviews and conversation turns of a workspace that run now have spent so far, per purse. The ledger gains
 * none of it until each of them ends.
 */
public record RunningSpend(BigDecimal instanceUsd, BigDecimal workspaceUsd) {
    public static final RunningSpend NONE = new RunningSpend(BigDecimal.ZERO, BigDecimal.ZERO);

    public RunningSpend plus(UsageFundingSource purse, BigDecimal usd) {
        return purse == UsageFundingSource.WORKSPACE
                ? new RunningSpend(instanceUsd, workspaceUsd.add(usd))
                : new RunningSpend(instanceUsd.add(usd), workspaceUsd);
    }
}
