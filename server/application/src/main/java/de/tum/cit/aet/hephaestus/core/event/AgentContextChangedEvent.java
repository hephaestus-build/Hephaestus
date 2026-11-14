package de.tum.cit.aet.hephaestus.core.event;

/**
 * What an agent may read for the workspace changed while it may run: a repository was hidden from contributions or
 * shown again, a Slack channel was paused or revoked, an Outline collection was paused or removed, or a connection
 * stopped being active. A running Hephaestus turn drops what it staged ({@code ConversationLedgerEvents.ContextChanged}).
 */
public record AgentContextChangedEvent(long workspaceId) {}
