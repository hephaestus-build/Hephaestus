package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.UUID;

/**
 * Deletes again, after a database restore, a developer's conversation message that the erasure recovery manifest
 * lists.
 */
public interface RestoredMessageDeletions {

    /**
     * Erases the message's words and every copy of them, as the developer's delete did.
     *
     * @return whether it erased: false when the restore has no such message in the workspace or it is erased already
     */
    boolean deleteAgain(long workspaceId, UUID entryId);
}
