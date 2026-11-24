package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * The kinds of due work that the owner function {@code mentoring_due_work} names: the registry that the function's
 * branches must match, each with the work item that owns it and the tables it reads. Each kind returns only the kind,
 * the key, the workspace, the account and the due time of a due row; the job then binds that account with
 * {@link OwnerScope#ofJob} and reads the row as its owner. A new kind adds its branch to the function in the same
 * change set and its constant here.
 */
public enum DueWorkKind {
    /**
     * KR-08: conversation entries written before the retention cutoff and not erased yet. Reads
     * {@code conversation_entry}, {@code conversation}.
     */
    ENTRY_RETENTION
}
