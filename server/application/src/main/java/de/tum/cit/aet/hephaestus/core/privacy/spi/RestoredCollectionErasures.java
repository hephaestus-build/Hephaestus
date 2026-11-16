package de.tum.cit.aet.hephaestus.core.privacy.spi;

/**
 * Erases again, after a database restore, a documentation collection that an admin erased from a workspace's mirror
 * and that the erasure recovery manifest lists.
 */
public interface RestoredCollectionErasures {

    /**
     * Deletes the collection's mirrored documents and its registration, as the admin's erase did.
     *
     * @return whether it erased: false when the restore holds neither the registration nor a mirrored document of it
     */
    boolean eraseAgain(long workspaceId, long connectionId, String collectionId);
}
