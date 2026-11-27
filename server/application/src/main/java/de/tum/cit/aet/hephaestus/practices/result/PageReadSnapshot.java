package de.tum.cit.aet.hephaestus.practices.result;

/**
 * The transaction of a practice page read: standings, group standings, the profile overview and its review list. Each
 * runs as one read-only REPEATABLE READ snapshot, so every source, access and consent check of one page load reads the
 * database as of one moment. A page then never shows two states across its sections, and a check that repeats inside
 * it can be kept ({@code SnapshotMemo}). A withdrawal that commits during a load shows on the next load.
 */
public final class PageReadSnapshot {
    /**
     * How long one page read may hold its snapshot. A longer read ends with an error rather than hold back vacuum.
     * PostgreSQL raises no serialization failure in a read-only transaction, so no read retries.
     */
    public static final int TIMEOUT_SECONDS = 30;

    private PageReadSnapshot() {}
}
