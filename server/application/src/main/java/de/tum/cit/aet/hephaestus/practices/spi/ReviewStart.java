package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** Parent review lock and start transition shared by inventory and job claims. */
public interface ReviewStart {
    /**
     * Locks the workspace's review before the caller locks its job. Returns false for a terminal or cancelled
     * review. Otherwise starts a queued review, or retains a running review. The caller's transaction must include
     * the job claim; it must roll back if no job is claimed.
     */
    boolean compose(long workspaceId, UUID reviewId);

    boolean start(long workspaceId, UUID reviewId);

    /**
     * Ends a queued event review whose work has moved: a newer occasion of the item that joins a window waits at another
     * version. Its practices end withheld as {@code REPLACED}, and the next readiness edge of the item selects them again.
     * The caller's transaction holds the parent's lock and must cancel the job in the same commit.
     *
     * @return whether the review ended here
     */
    boolean replaceByNewerOccasion(long workspaceId, UUID reviewId);
}
