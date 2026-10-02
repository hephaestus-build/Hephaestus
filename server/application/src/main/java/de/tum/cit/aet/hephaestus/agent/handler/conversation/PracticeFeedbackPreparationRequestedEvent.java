package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import java.util.UUID;

/**
 * Requests IN_CHAT and IN_APP preparation from a review's recorded observations. This does not assert
 * that feedback has been prepared or delivered; consumers run after the producing transaction commits.
 */
public record PracticeFeedbackPreparationRequestedEvent(UUID agentJobId, Long workspaceId) {}
