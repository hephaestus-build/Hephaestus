package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * Removes what a workspace's connections created at their providers, such as webhooks, before the purge erases the
 * credentials that a removal needs.
 */
public interface WorkspaceProviderRemoval {
    /**
     * Runs with no transaction open, before the purge. A removal that does not finish stays recorded for an
     * instance admin; only a failure to disconnect propagates, and then the purge does not start.
     */
    void removeBeforePurge(long workspaceId);
}
