package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * @param occasions for an event review, the names of every occasion that joined its quiet window, the event's own
 *     included; they select the practices. Empty for any other origin.
 * @param commentAnswer for a request made by a comment on the work, where to answer it when no feedback does
 * @param scanRef for a scan, the branch the requester asks for; the scan is refused unless it is the default branch
 * @param expectedVersion for a scan, the version the requester expects; the scan is refused unless it pins that one
 */
@Builder
public record ReviewRequest(
        long workspaceId,
        long itemId,
        ReviewKind kind,
        @Nullable ItemVersion version,
        ReviewOrigin origin,
        @Nullable UUID eventId,
        @Nullable Long requestedById,
        @Nullable UUID batchId,
        @Nullable BigDecimal budgetUsd,
        @Nullable UUID requestKey,
        @Nullable String requestDigest,
        List<Long> practiceIds,
        boolean reuse,
        Set<ItemEventName> occasions,
        @Nullable CommentAnswer commentAnswer,
        @Nullable String scanRef,
        @Nullable String expectedVersion) {
    /** The thread of a request comment, in the address form of the adapter of {@code kind}. */
    public record CommentAnswer(IntegrationKind kind, String address) {}

    public ReviewRequest {
        practiceIds = List.copyOf(practiceIds);
        occasions = occasions == null ? Set.of() : Set.copyOf(occasions);
    }
}
