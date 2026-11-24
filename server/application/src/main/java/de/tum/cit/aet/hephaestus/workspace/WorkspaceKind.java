package de.tum.cit.aet.hephaestus.workspace;

/** What a workspace is for. A workspace never changes its kind. */
public enum WorkspaceKind {
    /** A team's workspace, connected to its providers, with members and admins. */
    TEAM,
    /** A person's own space for goals and reflection. Only its owner enters it, and it belongs to no team. */
    PERSONAL,
}
