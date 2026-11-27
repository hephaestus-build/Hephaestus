package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A new advice revision from one sealed review. {@code citations} are what the text relies on, at least one of this
 * practice; {@code otherObservations} are the rest of what the model saw. {@code seenVersion} is the version that
 * {@link PracticeAdviceReads#prior} returned, null when there was no entry.
 */
public record ReviseAdvice(
        long workspaceId,
        long accountId,
        long practiceId,
        UUID reviewId,
        @Nullable Long seenVersion,
        String text,
        List<EvidenceRef> citations,
        List<EvidenceRef> otherObservations) {
    public ReviseAdvice {
        citations = List.copyOf(citations);
        otherObservations = List.copyOf(otherObservations);
    }
}
