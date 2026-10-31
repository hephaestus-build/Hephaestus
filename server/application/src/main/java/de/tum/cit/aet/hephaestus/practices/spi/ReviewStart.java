package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** Parent review lock and start transition shared by inventory and job claims. */
public interface ReviewStart {
    /**
     * Locks the workspace's review before the caller locks its job. Returns false for a terminal or cancelled
     * review. Otherwise starts a queued review, or retains a running review. The caller's transaction must include
     * the job claim; it must roll back if no job is claimed.
     */
    boolean start(long workspaceId, UUID reviewId);
}
