package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record FeedbackHistoryEntry(
        UUID feedbackId,
        UUID reviewId,
        @Nullable Long itemId,
        long developerAccountId,
        FeedbackDestination destination,
        FeedbackState state,
        @Nullable String body,
        Instant createdAt,
        @Nullable Instant deliveredAt,
        List<UUID> resultIds,
        List<UUID> problemIds,
        List<String> practiceSlugs,
        @Nullable FeedbackResolution resolution,
        @Nullable FeedbackUsefulness usefulness,
        List<UUID> sourceJobIds,
        List<PersonCopyIdentity> copiedIdentities,
        List<Long> repositoryIds,
        @Nullable UUID threadId,
        boolean copyAuthorityComplete,
        @Nullable JsonNode publicHistory) {
    public FeedbackHistoryEntry {
        resultIds = List.copyOf(resultIds);
        problemIds = List.copyOf(problemIds);
        practiceSlugs = List.copyOf(practiceSlugs);
        sourceJobIds = List.copyOf(sourceJobIds);
        copiedIdentities = List.copyOf(copiedIdentities);
        repositoryIds = List.copyOf(repositoryIds);
        publicHistory = publicHistory == null ? null : publicHistory.deepCopy();
    }

    @Override
    @RawObservationAccess
    public @Nullable JsonNode publicHistory() {
        return publicHistory == null ? null : publicHistory.deepCopy();
    }
}
