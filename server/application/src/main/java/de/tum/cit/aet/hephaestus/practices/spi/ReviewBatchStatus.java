package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Review batch transitions. A nonterminal state can repeat; a terminal state has no next state.
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
public enum ReviewBatchStatus {
    AWAITING_CONFIRMATION,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    BUDGET_EXHAUSTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == BUDGET_EXHAUSTED;
    }
}
