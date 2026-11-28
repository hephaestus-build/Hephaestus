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
 * @param coalesced for an event review, the older occasions of its quiet window, which its review coalesces
 * @param commentAnswer for a request made by a comment on the work, where to answer it when no feedback does
 * @param scanRef for a scan, the branch the requester asks for; the scan is refused unless it is the default branch
 * @param expectedVersion for a scan, the version the requester expects; the scan is refused unless it pins that one
 * @param practiceVersionId for a trial, the version it tries, which need not be current; null tries the current one
 * @param setupId the review setup that starts the review, or null for a review that no setup starts
 * @param audience who reads the review (ADR 0167 decision 3). Null takes the rule of a review without a setup: a scan or
 *     a trial is operational, and any other review is personal.
 * @param audienceTeamId the team project of a {@code TEAM} audience, and of no other
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
        Set<UUID> coalesced,
        @Nullable CommentAnswer commentAnswer,
        @Nullable String scanRef,
        @Nullable String expectedVersion,
        @Nullable Long practiceVersionId,
        @Nullable UUID setupId,
        @Nullable ReviewAudience audience,
        @Nullable UUID audienceTeamId) {
    /** The thread of a request comment, in the address form of the adapter of {@code kind}. */
    public record CommentAnswer(IntegrationKind kind, String address) {}

    public ReviewRequest {
        practiceIds = List.copyOf(practiceIds);
        occasions = occasions == null ? Set.of() : Set.copyOf(occasions);
        coalesced = coalesced == null ? Set.of() : Set.copyOf(coalesced);
        if ((audienceTeamId != null) != (audience == ReviewAudience.TEAM)) {
            throw new IllegalArgumentException(
                    "A team audience names its team project, and no other audience names one.");
        }
    }
}
