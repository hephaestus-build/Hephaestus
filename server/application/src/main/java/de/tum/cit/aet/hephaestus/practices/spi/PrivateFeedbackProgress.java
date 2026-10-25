package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The private feedback one review already prepared for one developer, read from its prepared rows. A piece decides
 * every admitted result its basis names, whether it delivers or withholds. A piece composed to be delivered uses its
 * destination's room even when the server withholds it later; a composer's withholding uses none.
 *
 * @param decidedResultIds the admitted results any private piece decides, on either destination
 * @param decidedByDestination the admitted results each private destination's pieces decide: a page card decides
 *     nothing for the conversation
 */
public record PrivateFeedbackProgress(
        Set<UUID> decidedResultIds,
        int practicePageDeliveries,
        int conversationDeliveries,
        Map<FeedbackDestination, Set<UUID>> decidedByDestination) {
    public static final PrivateFeedbackProgress NONE = new PrivateFeedbackProgress(Set.of(), 0, 0, Map.of());

    public PrivateFeedbackProgress {
        decidedResultIds = Set.copyOf(decidedResultIds);
        var copied = new EnumMap<FeedbackDestination, Set<UUID>>(FeedbackDestination.class);
        decidedByDestination.forEach((destination, results) -> copied.put(destination, Set.copyOf(results)));
        decidedByDestination = Collections.unmodifiableMap(copied);
    }
}
