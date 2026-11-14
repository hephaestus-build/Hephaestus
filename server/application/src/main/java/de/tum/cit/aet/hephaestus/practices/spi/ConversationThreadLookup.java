package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Practices-owned port to the Hephaestus conversation (dependency inversion): where a Hephaestus message that showed feedback
 * sits, so a link to the message can open its thread. The conversation module implements it.
 */
public interface ConversationThreadLookup {

    /** Where a message sits: in the thread on {@code threadParentId}, or in the main conversation. */
    record Place(@Nullable UUID threadParentId) {}

    /** Where each of the messages sits that the conversation still shows; an erased message has no place. */
    Map<UUID, Place> places(long workspaceId, Collection<UUID> messageIds);
}
