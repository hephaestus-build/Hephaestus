package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.UUID;

/**
 * Withdraws again, after a database restore, submitted team work that the erasure recovery manifest lists. Only a
 * version that is still available changes, so an erased or expired version keeps its state.
 */
public interface RestoredTeamWork {

    /**
     * Withdraws the version, which deletes its content.
     *
     * @return whether it withdrew: false when the restore holds no available version of that id in the workspace
     */
    boolean withdrawAgain(long workspaceId, UUID versionId);

    /**
     * Withdraws every available version that the account submitted in the workspace, as leaving it did.
     *
     * @return how many it withdrew
     */
    int withdrawDepartedAgain(long workspaceId, long accountId);
}
