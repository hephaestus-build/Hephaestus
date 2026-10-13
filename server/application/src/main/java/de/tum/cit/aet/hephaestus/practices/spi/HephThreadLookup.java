package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Practices-owned port to the Heph conversation (dependency inversion): where a Heph message that showed feedback
 * sits, so a link to the message can open its thread. The mentor module implements it.
 */
public interface HephThreadLookup {

    /** Where a message sits: in the thread on {@code threadParentId}, or in the main conversation. */
    record Place(@Nullable UUID threadParentId) {}

    /** Where each of the messages sits that the conversation still shows; an erased message has no place. */
    Map<UUID, Place> places(long workspaceId, Collection<UUID> messageIds);
}
