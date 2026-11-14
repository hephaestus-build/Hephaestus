package de.tum.cit.aet.hephaestus.agent.conversation.sandbox;

import java.util.UUID;

/**
 * A Hephaestus turn attempt that runs on this server. Its token names the turn and the attempt; the claimed turn names its
 * conversation, and the conversation its developer's account. The turn's tools act only within these.
 */
public record TurnAttempt(
        UUID turnId, Long workspaceId, Long accountId, UUID conversationId, int attempt, UUID runId) {}
