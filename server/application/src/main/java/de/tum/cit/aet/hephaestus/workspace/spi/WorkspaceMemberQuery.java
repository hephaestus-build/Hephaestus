package de.tum.cit.aet.hephaestus.workspace.spi;

/** Whether a synced developer is currently a member of a workspace. */
public interface WorkspaceMemberQuery {

    boolean isMember(long workspaceId, long userId);
}
