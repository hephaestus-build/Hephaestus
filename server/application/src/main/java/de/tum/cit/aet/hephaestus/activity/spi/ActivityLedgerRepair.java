package de.tum.cit.aet.hephaestus.activity.spi;

/** Repairs reconstructible activity after provider data has committed. */
public interface ActivityLedgerRepair {
    int reconcileRepository(long workspaceId, long repositoryId);
}
