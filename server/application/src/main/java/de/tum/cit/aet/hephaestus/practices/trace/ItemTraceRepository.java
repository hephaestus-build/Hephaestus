package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ItemTraceRepository extends Repository<Review, UUID> {
    String EVENT_COLUMNS = "id,item_id AS itemId,name,item_version AS itemVersion,occurred_at AS occurredAt,"
            + "found_by AS foundBy,state,reason,decided_at AS decidedAt,"
            + "(SELECT r.id FROM review r WHERE r.workspace_id=item_event.workspace_id"
            + " AND r.event_id=item_event.id) AS reviewId";

    @Query(
            value = "SELECT " + EVENT_COLUMNS + " FROM item_event WHERE workspace_id=:ws AND id IN (:ids)",
            nativeQuery = true)
    List<EventRow> events(long ws, Collection<UUID> ids);

    @Query(value = """
        SELECT q.* FROM review q WHERE q.workspace_id=:ws AND q.id IN (
          SELECT r.id FROM review r WHERE r.workspace_id=:ws AND r.item_id=:item
          UNION SELECT m.review_id FROM review_item m WHERE m.workspace_id=:ws AND m.item_id=:item)
        ORDER BY q.ordered_at DESC,q.id::text DESC LIMIT 100
        """, nativeQuery = true)
    List<Review> reviewsForItem(long ws, long item);

    @Query(
            value = "SELECT " + EVENT_COLUMNS
                    + " FROM item_event WHERE workspace_id=:ws AND item_id=:item ORDER BY occurred_at"
                    + " DESC,id::text DESC",
            countQuery = "SELECT count(*) FROM item_event WHERE workspace_id=:ws AND item_id=:item",
            nativeQuery = true)
    Page<EventRow> events(long ws, long item, Pageable page);

    interface EventRow {
        UUID getId();

        long getItemId();

        String getName();

        String getItemVersion();

        Instant getOccurredAt();

        FoundBy getFoundBy();

        ItemEventState getState();

        @Nullable
        ReviewRequestReason getReason();

        @Nullable
        Instant getDecidedAt();

        @Nullable
        UUID getReviewId();
    }
}
