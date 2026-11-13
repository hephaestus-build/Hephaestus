package de.tum.cit.aet.hephaestus.workspace;

/** The active-slug lookup needs only the ID, without the tenant's managed state. */
public record WorkspaceIdData(long id) {}
