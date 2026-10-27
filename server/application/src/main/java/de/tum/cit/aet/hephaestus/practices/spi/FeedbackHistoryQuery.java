package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface FeedbackHistoryQuery {
    /**
     * The developer's own feedback in the {@linkplain FeedbackState#VISIBLE_TO_DEVELOPER states they may learn of},
     * without withdrawn pieces, as their Practice profile shows it.
     */
    List<FeedbackHistoryEntry> forDeveloper(long workspaceId, long developerAccountId, Instant since, int limit);

    /**
     * The item's feedback that {@code viewerAccountId} may read: their own in every state, and delivered feedback on the
     * work. Not a developer-facing read: its caller keeps only delivered feedback on the work.
     */
    List<FeedbackHistoryEntry> forItem(long workspaceId, long itemId, long viewerAccountId, int limit);

    /**
     * Private feedback delivered to one developer, and conversation feedback still queued for them, on exactly the
     * {@code routes} given: a row is read only on its own destination and when it rests on that route's practice.
     * Empty when the developer is not an active member, which is a read not made, not an empty history.
     */
    Optional<PrivateFeedbackHistory> forPrivateComposition(
            long workspaceId, long developerAccountId, Collection<PrivateFeedbackHistory.Route> routes, int limit);

    /** One piece of the developer's own feedback, as {@link #forDeveloper} reads it; empty when it is not theirs. */
    Optional<FeedbackHistoryEntry> find(long workspaceId, UUID feedbackId, long developerAccountId);

    /** The publication context of the same read; issue it inside the snapshot that reads the history page. */
    String publicationContext(long workspaceId, long itemId, UUID reviewId, Collection<Long> developerIds);

    /** Why a public piece composed from {@code publicationContext} cannot be prepared now. */
    Optional<FeedbackStopReason> publicationRefusal(
            long workspaceId, long itemId, UUID reviewId, long developerId, @Nullable String publicationContext);
}
