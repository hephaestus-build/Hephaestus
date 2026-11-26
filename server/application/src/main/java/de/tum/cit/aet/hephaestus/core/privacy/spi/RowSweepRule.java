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

    /**
     * #5090: expires an account's ready exports when an erasure or an edit changes one of their sources. The triggers
     * on {@code conversation_entry}, {@code feedback}, {@code feedback_copy} and {@code feedback_response} call it; it
     * takes the account's export fence shared. Changes {@code account_export}.
     */
    EXPORT_SOURCES_CHANGED(false);

    private final boolean requiresLifecycleAdmission;

    RowSweepRule(boolean requiresLifecycleAdmission) {
        this.requiresLifecycleAdmission = requiresLifecycleAdmission;
    }

    public boolean requiresLifecycleAdmission() {
        return requiresLifecycleAdmission;
    }
}
