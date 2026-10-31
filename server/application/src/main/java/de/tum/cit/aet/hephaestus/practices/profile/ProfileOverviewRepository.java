package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ProfileOverviewRepository extends Repository<Result, UUID> {
    @Query(value = """
        SELECT p.slug,min(r.recorded_at) AS recordedAt FROM result r JOIN practice p ON p.id=r.practice_id
          AND p.workspace_id=r.workspace_id WHERE r.workspace_id=:ws AND r.developer_id=:account GROUP BY p.slug
        """, nativeQuery = true)
    List<FirstOpportunity> firstOpportunities(long ws, long account);

    @Query(value = """
        SELECT feedback_id AS feedbackId,min(created_at) AS createdAt FROM feedback_response
        WHERE workspace_id=:ws AND feedback_id IN (:ids) AND resolution IN ('ADDRESSED','NOT_APPLICABLE')
          AND created_at<=:until GROUP BY feedback_id
        """, nativeQuery = true)
    List<AddressedFeedback> addressed(long ws, Collection<UUID> ids, Instant until);

    /**
     * The newest item review in flight whose frozen subject is the account and that has recorded no result about the
     * account yet. The requester does not own a review; the subject that selection froze on its listed item does.
     */
    @Query(value = """
        SELECT q.id AS reviewId,q.item_id AS itemId,q.ordered_at AS orderedAt,""" + ReviewShownStatus.SQL + """
         AS status FROM review q WHERE q.workspace_id=:ws AND q.kind='ITEM' AND q.status IN ('QUEUED','RUNNING')
          AND NOT COALESCE((q.checks->>'cancellationRequested')::boolean,false)
          AND EXISTS(SELECT 1 FROM review_item m WHERE m.workspace_id=q.workspace_id AND m.review_id=q.id
            AND m.state='LISTED' AND m.detail->>'developerId'=CAST(:account AS text))
          AND NOT EXISTS(SELECT 1 FROM result r WHERE r.workspace_id=q.workspace_id AND r.review_id=q.id
            AND r.developer_id=:account)
        ORDER BY q.ordered_at DESC,q.id DESC LIMIT 1
        """, nativeQuery = true)
    Optional<ActiveReview> activeReview(long ws, long account);

    interface ActiveReview {
        UUID getReviewId();

        long getItemId();

        Instant getOrderedAt();

        String getStatus();
    }

    interface FirstOpportunity {
        String getSlug();

        Instant getRecordedAt();
    }

    interface AddressedFeedback {
        UUID getFeedbackId();

        Instant getCreatedAt();
    }
}
