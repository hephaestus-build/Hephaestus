package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Disconnects again, after a database restore, a connection that the erasure recovery manifest lists. */
public interface RestoredDisconnects {

    /**
     * Erases the connection's local data and disconnects it without calling its provider; the provider removal it
     * records runs once provider egress is open again.
     *
     * @return whether it erased: false when the restore has no such connection in the workspace or it is disconnected
     *     already
     */
    boolean disconnectAgain(long workspaceId, long connectionId);
}
