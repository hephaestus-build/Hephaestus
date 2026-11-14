package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Disconnects again, after a database restore, a connection that the erasure recovery manifest lists. */
public interface RestoredDisconnects {

    /**
     * Erases the connection's local data, releases its credential and disconnects it. The provider removal that it
     * records is tried after commit and retried until provider egress is open again; the erasure never waits for it.
     *
     * @return whether it erased: false when the restore has no such connection in the workspace or it is disconnected
     *     already
     */
    boolean disconnectAgain(long workspaceId, long connectionId);
}
