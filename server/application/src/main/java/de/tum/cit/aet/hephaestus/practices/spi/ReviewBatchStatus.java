package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Review batch transitions. A nonterminal state can repeat; a terminal state has no next state.
 * {@code ReviewBatch} changes its status only through its transition methods, which check this table.
 *
 * <table>
 * <caption>Legal state changes</caption>
 * <tr><th>Current state</th><th>Next states</th></tr>
 * <tr><td>AWAITING_CONFIRMATION</td><td>RUNNING, PAUSED, CANCELLED</td></tr>
 * <tr><td>RUNNING</td><td>PAUSED, COMPLETED, CANCELLED, BUDGET_EXHAUSTED</td></tr>
 * <tr><td>PAUSED</td><td>RUNNING, COMPLETED, CANCELLED, BUDGET_EXHAUSTED</td></tr>
 * <tr><td>COMPLETED, CANCELLED, BUDGET_EXHAUSTED</td><td>None</td></tr>
 * </table>
 *
 * <p>Only a scheduled batch whose verified spend exhausted its fixed cap enters BUDGET_EXHAUSTED.
 */
public enum ReviewBatchStatus implements Lifecycle<ReviewBatchStatus> {
    AWAITING_CONFIRMATION,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    BUDGET_EXHAUSTED;

    private static final Map<ReviewBatchStatus, Set<ReviewBatchStatus>> LEGAL = Map.of(
            AWAITING_CONFIRMATION, Set.of(AWAITING_CONFIRMATION, RUNNING, PAUSED, CANCELLED),
            RUNNING, Set.of(RUNNING, PAUSED, COMPLETED, CANCELLED, BUDGET_EXHAUSTED),
            PAUSED, Set.of(PAUSED, RUNNING, COMPLETED, CANCELLED, BUDGET_EXHAUSTED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of(),
            BUDGET_EXHAUSTED, Set.of());

    @Override
    public boolean canTransitionTo(ReviewBatchStatus next) {
        return Objects.requireNonNull(LEGAL.get(this)).contains(next);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == BUDGET_EXHAUSTED;
    }
}
