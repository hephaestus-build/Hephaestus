package de.tum.cit.aet.hephaestus.workspace.spi;

/** Whether a workspace lets Hephaestus start messages to its people (ADR 0139 decision 15). */
public interface WorkspaceStartedMessages {
    /** False for an unknown workspace: a rule that cannot be evaluated removes the effect. */
    boolean startedMessagesEnabled(long workspaceId);
}
