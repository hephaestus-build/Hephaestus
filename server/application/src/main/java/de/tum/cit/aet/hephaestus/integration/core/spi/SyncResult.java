package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Result of a synchronization operation: a completion {@link Status} plus the count of items synced
 * before completion or abort.
 *
 * <p>The persisted sync timestamp should only be advanced when the status is {@link
 * Status#COMPLETED}, so an incomplete or aborted sync is retried from the same point on the next
 * run.
 *
 * @param status the completion status of the sync operation
 * @param count  the number of items successfully synced before completion or abort
 */
public record SyncResult(Status status, int count) {
    /**
     * The completion status of a sync operation.
     */
    public enum Status {
        /**
         * Sync completed successfully - all pages and phases were fetched. The timestamp should be
         * updated.
         */
        COMPLETED(0),

        /**
         * Sync completed but with warnings - the primary phase succeeded but one or more secondary
         * phases failed (e.g., item sync succeeded but field sync or status update sync failed).
         * <p>
         * The timestamp for successfully completed phases should be updated. Failed phases retain
         * their previous timestamp for retry.
         */
        COMPLETED_WITH_WARNINGS(1),

        /**
         * Sync was aborted due to rate limiting. The timestamp should NOT be updated to allow retry
         * from the same point.
         */
        ABORTED_RATE_LIMIT(2),

        /**
         * Sync was aborted due to an error (auth, not found, client error, etc.). The timestamp
         * should NOT be updated to allow retry from the same point.
         */
        ABORTED_ERROR(3);

        /** How bad the outcome is; declared so reordering the constants cannot change {@link #merge}. */
        private final int badness;

        Status(int badness) {
            this.badness = badness;
        }

        public boolean isWorseThan(Status other) {
            return badness > other.badness;
        }
    }

    /**
     * Creates a successful completion result.
     *
     * @param count the number of items synced
     * @return a SyncResult with COMPLETED status
     */
    public static SyncResult completed(int count) {
        return new SyncResult(Status.COMPLETED, count);
    }

    /**
     * Creates a completion result with warnings, used when the primary sync (e.g. items) succeeded
     * but a secondary phase (e.g. fields, status updates) failed.
     *
     * @param count the number of items synced
     * @return a SyncResult with COMPLETED_WITH_WARNINGS status
     */
    public static SyncResult completedWithWarnings(int count) {
        return new SyncResult(Status.COMPLETED_WITH_WARNINGS, count);
    }

    /**
     * Creates an aborted result due to rate limiting.
     *
     * @param count the number of items synced before abort
     * @return a SyncResult with ABORTED_RATE_LIMIT status
     */
    public static SyncResult abortedRateLimit(int count) {
        return new SyncResult(Status.ABORTED_RATE_LIMIT, count);
    }

    /**
     * Creates an aborted result due to an error.
     *
     * @param count the number of items synced before abort
     * @return a SyncResult with ABORTED_ERROR status
     */
    public static SyncResult abortedError(int count) {
        return new SyncResult(Status.ABORTED_ERROR, count);
    }

    /**
     * Checks if the sync completed successfully (fully or with warnings).
     *
     * @return true if status is COMPLETED or COMPLETED_WITH_WARNINGS
     */
    public boolean isCompleted() {
        return status == Status.COMPLETED || status == Status.COMPLETED_WITH_WARNINGS;
    }

    /**
     * Checks if the sync was aborted for any reason.
     *
     * @return true if status is ABORTED_RATE_LIMIT or ABORTED_ERROR
     */
    public boolean isAborted() {
        return status == Status.ABORTED_RATE_LIMIT || status == Status.ABORTED_ERROR;
    }

    /**
     * Merges multiple sync results into one by summing counts and picking the worst status.
     * <p>
     * Used when a single logical sync is split into multiple passes (e.g., by PR state).
     *
     * @param results the results to merge
     * @return a merged SyncResult with aggregated count and worst status
     */
    public static SyncResult merge(SyncResult... results) {
        int totalCount = 0;
        Status worstStatus = Status.COMPLETED;
        for (SyncResult r : results) {
            totalCount += r.count();
            if (r.status().isWorseThan(worstStatus)) {
                worstStatus = r.status();
            }
        }
        return new SyncResult(worstStatus, totalCount);
    }
}
