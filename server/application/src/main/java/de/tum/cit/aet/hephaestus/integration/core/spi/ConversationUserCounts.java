package de.tum.cit.aet.hephaestus.integration.core.spi;

/** One-row aggregate of the developer's workspace work. */
public record ConversationUserCounts(
        Long openPRs,
        Long mergedThisWeek,
        Long mergedLastWeek,
        Long openIssues,
        Long reviewsGivenThisWeek,
        Long reviewsGivenLastWeek,
        Long reviewsReceivedThisWeek,
        Long pendingReviewRequests,
        Long unresolvedThreads) {}
