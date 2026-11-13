package de.tum.cit.aet.hephaestus.core.event;

/**
 * Signals that workspace entities exist in the database and installation-level provider inbox
 * consumers (in {@code integration.scm.sync}) may start processing webhook events.
 *
 * <p>Lives in {@code core.event} as a deliberate dependency-inversion: workspace publishes,
 * integration.scm listens. Putting the event in either feature module forms a cycle with the
 * other, because {@code workspace.adapter} already implements {@code integration.spi.*}.
 *
 * @param workspaceCount number of workspaces that were initialized
 */
public record WorkspacesInitializedEvent(int workspaceCount) {}
