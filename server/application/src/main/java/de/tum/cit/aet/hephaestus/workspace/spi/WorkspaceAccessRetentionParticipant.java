package de.tum.cit.aet.hephaestus.workspace.spi;

/** External providers keep unresolved obligations and erase finished access data with its request history. */
public interface WorkspaceAccessRetentionParticipant {
    /** Called under the workspace write lock; errors abort cleanup rather than permitting it. */
    boolean canEraseAccessRequests(Long workspaceId, Long accountId);

    /** Called only after every participant permits cleanup, in the same workspace-locked transaction. */
    void eraseAccessRequestData(Long workspaceId, Long accountId);
}
