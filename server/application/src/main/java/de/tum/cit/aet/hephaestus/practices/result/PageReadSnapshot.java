package de.tum.cit.aet.hephaestus.practices.result;

/**
 * The transaction of a practice page read: standings, group standings, the profile overview and its review list. Each
 * request runs as one read-only REPEATABLE READ snapshot, so the source, access and consent checks of one request read
 * the database as of one moment, and a check that repeats inside it can be kept ({@code SnapshotMemo}). A withdrawal
 * that commits during a request shows on the next one. A page that makes several requests reads several moments.
 * Checks that run in a transaction or connection of their own still read the latest data, as before: the review job
 * erasure holds and the copy fence's custody receipts.
 */
public final class PageReadSnapshot {
    /**
     * How long one page read may hold its snapshot. A longer read ends with an error rather than hold back vacuum.
     * PostgreSQL raises no serialization failure in a read-only transaction, so no read retries.
     */
    public static final int TIMEOUT_SECONDS = 30;

    private PageReadSnapshot() {}
}
