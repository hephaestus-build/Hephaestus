package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The closed rules by which the owner function {@code person_row_sweep} changes or deletes rows across owners: the
 * registry that the function's branches must match, each with the work item that owns it and the tables it reads or
 * changes. A new rule adds its branch to the function in the same change set and its constant here.
 */
public enum RowSweepRule {
    /**
     * KR-08: deletes a purged workspace's conversations. Changes {@code conversation_entry}, {@code conversation_turn},
     * {@code conversation}.
     */
    WORKSPACE_PURGE(true),

    /**
     * KR-08: expires ready account exports past their download window and drops their payload. Changes
     * {@code account_export}.
     */
    EXPIRED_EXPORTS(false),

    /**
     * KR-08: moves an unclaimed account's conversations to the account that claimed it. Reads {@code account},
     * {@code agent_run}; changes {@code conversation_entry}, {@code conversation_turn}, {@code conversation}.
     */
    ACCOUNT_ABSORB(true),

    /** CV-05: deletes create receipts older than eight days, at most 1000 a call. Changes {@code client_input}. */
    CLIENT_INPUT_EXPIRY(false);

    private final boolean requiresLifecycleAdmission;

    RowSweepRule(boolean requiresLifecycleAdmission) {
        this.requiresLifecycleAdmission = requiresLifecycleAdmission;
    }

    public boolean requiresLifecycleAdmission() {
        return requiresLifecycleAdmission;
    }
}
