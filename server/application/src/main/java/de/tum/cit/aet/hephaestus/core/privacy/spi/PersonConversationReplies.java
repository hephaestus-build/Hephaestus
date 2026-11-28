package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Where Hephaestus's Slack reply may hold the words of a conversation copy that a person's erasure selects (#5440). A
 * reply leaves Hephaestus only when a Slack turn ends done, and it can hold only what its final attempt claimed.
 */
@FunctionalInterface
public interface PersonConversationReplies {
    /** The turn that claimed a conversation copy, and when. */
    record Claim(UUID turnId, Instant claimedAt) {}

    /**
     * The locator of the Slack reply that may hold each claim's words, for claims of one workspace. A claim whose turn
     * posted no such reply is absent: its words stay in Hephaestus, which the erasure redacts.
     */
    Map<Claim, String> slackReplies(long workspaceId, Collection<Claim> claims);
}
