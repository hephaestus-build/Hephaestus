package de.tum.cit.aet.hephaestus.practices.spi;

/**
 * Whether Hephaestus can answer in a workspace: the workspace is active and one of its enabled Hephaestus model
 * bindings resolves to an available model. Admins turn Hephaestus on and off through those bindings.
 * Implementations fail closed.
 */
public interface ConversationReadinessQuery {
    boolean isReady(long workspaceId);
}
