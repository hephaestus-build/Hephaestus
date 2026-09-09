package de.tum.cit.aet.hephaestus.workspace.spi;

/** External access providers retain the evidence they still need to finish revocation. */
public interface WorkspaceAccessRetentionGuard {
    /** Called under the workspace write lock; errors abort cleanup rather than permitting it. */
    boolean canEraseAccessRequests(Long workspaceId, Long accountId);
}
