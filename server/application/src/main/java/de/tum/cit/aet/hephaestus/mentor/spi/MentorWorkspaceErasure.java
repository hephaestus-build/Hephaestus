package de.tum.cit.aet.hephaestus.mentor.spi;

/** Erases every account's mentor rows of one workspace in the caller's transaction: on its purge and on a disconnect. */
public interface MentorWorkspaceErasure {
    void eraseWorkspace(long workspaceId);
}
