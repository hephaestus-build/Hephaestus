package de.tum.cit.aet.hephaestus.integration.chat.item;

import java.util.List;
import org.jspecify.annotations.Nullable;

public interface ConversationCandidateSource {
    /**
     * Threads on an {@code ACTIVE} channel that have grown past their review watermark and have at least
     * {@code minMessageCount} messages, oldest {@code last_ts} first. Gate evaluation (quiescence/depth/growth)
     * and enqueue are the caller's job.
     */
    List<ConversationThreadCandidate> settledCandidates(int minMessageCount);

    long liveTurnCount(long workspaceId, String channelId, String threadTs);

    /**
     * Count of non-tombstoned turns with {@code slack_ts} strictly greater than {@code watermark}
     * (lexicographic; a null watermark counts everything).
     */
    long liveTurnCountSince(long workspaceId, String channelId, String threadTs, @Nullable String watermark);

    void markReviewed(long workspaceId, long threadId, String lastTs);
}
