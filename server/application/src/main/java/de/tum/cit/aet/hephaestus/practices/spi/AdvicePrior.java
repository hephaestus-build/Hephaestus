package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An entry as the mentor's guidance decision reads it. It never carries advice text. {@code version} is the value to
 * pass as {@code seenVersion}, for every state. {@code latestRevisionId} is the key a conversation basis records.
 */
public record AdvicePrior(
        UUID adviceId,
        long version,
        @Nullable Integer latestRevision,
        @Nullable UUID latestRevisionId,
        PriorState state,
        @Nullable AdviceRetireReason reason,
        List<Long> dismissedItems,
        List<EvidenceRef> citations) {
    public AdvicePrior {
        dismissedItems = List.copyOf(dismissedItems);
        citations = List.copyOf(citations);
    }
}
