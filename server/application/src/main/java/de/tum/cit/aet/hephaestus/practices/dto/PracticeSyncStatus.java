package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a sync of the practice repository did with one folder, or with a practice whose folder is gone (ADR 0118). */
@Schema(name = "PracticeSyncStatus")
public enum PracticeSyncStatus {
    /** A new practice from the folder. It starts with feedback mode Off. */
    CREATED,
    /** The practice edited in the repository took the folder's changed definition or group. */
    UPDATED,
    /** The folder holds what the practice already has. */
    UNCHANGED,
    /** A draft practice with the folder's slug was put in use with the folder's definition and feedback mode Off. */
    TAKEN_OVER,
    /** The practice is edited in Hephaestus, so the folder does not change it. */
    EDITED_IN_HEPHAESTUS,
    /** The folder is not valid, so it changed nothing. Its problems say why. */
    INVALID,
    /**
     * The folder of a practice edited in the repository is gone, so nobody reviews the practice. Later syncs report it
     * again while the folder stays gone.
     */
    RETIRED,
    /**
     * The folder of a practice edited in the repository is gone, but its current version no longer passes the checks
     * of a save, so it cannot get a version that nobody reviews. The practice stays as it is. Its problems say why.
     */
    RETIRE_REFUSED,
    /**
     * The practice was created, moved to the repository or given a version while the folders were read, so this sync
     * left it alone. The next sync decides from a fresh read.
     */
    CHANGED_DURING_SYNC
}
