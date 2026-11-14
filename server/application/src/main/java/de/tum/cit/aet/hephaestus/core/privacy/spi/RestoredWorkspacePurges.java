package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Purges again, after a database restore, a workspace that the erasure recovery manifest lists. */
public interface RestoredWorkspacePurges {

    /** @return whether it purged: false when the restore has no such workspace or it is purged already */
    boolean purgeAgain(long workspaceId);
}
