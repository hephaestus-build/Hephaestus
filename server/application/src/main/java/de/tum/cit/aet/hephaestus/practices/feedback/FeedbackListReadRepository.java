package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface FeedbackListReadRepository extends Repository<Feedback, UUID> {
    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.feedback.FeedbackListReadRepository$CopyCount(
            c.feedbackId, c.state, COUNT(c), MAX(CASE WHEN c.body IS NOT NULL THEN 1 ELSE 0 END))
        FROM FeedbackCopy c WHERE c.workspaceId = :workspaceId AND c.feedbackId IN :feedbackIds
        GROUP BY c.feedbackId, c.state
        """)
    List<CopyCount> copyCounts(long workspaceId, Collection<UUID> feedbackIds);

    record CopyCount(UUID feedbackId, FeedbackCopyState state, long count, int bodyPresent) {}

    @Query("""
        SELECT new de.tum.cit.aet.hephaestus.practices.feedback.FeedbackListReadRepository$ResponseTime(
            r.feedbackId, r.resolution, r.createdAt)
        FROM FeedbackResponse r WHERE r.workspaceId = :workspaceId AND r.feedbackId IN :feedbackIds
          AND NOT EXISTS (SELECT newer.id FROM FeedbackResponse newer
            WHERE newer.workspaceId = :workspaceId AND newer.feedbackId = r.feedbackId
              AND (newer.createdAt > r.createdAt OR (newer.createdAt = r.createdAt
                AND CAST(newer.id AS string) > CAST(r.id AS string))))
        """)
    List<ResponseTime> responseTimes(long workspaceId, Collection<UUID> feedbackIds);

    record ResponseTime(UUID feedbackId, @Nullable FeedbackResolution resolution, Instant createdAt) {}
}
