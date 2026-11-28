package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ProductItemRepository extends Repository<Review, UUID> {
    @Query(value = """
        SELECT id FROM item WHERE workspace_id=:workspaceId AND parent_id=:parentId AND kind='scm.file'
        AND removed_at IS NULL AND created_at<:before ORDER BY id FOR UPDATE
        """, nativeQuery = true)
    List<Long> lockPreviousFiles(long workspaceId, long parentId, Instant before);

    @Query(
            value = "SELECT COUNT(*) FROM item WHERE workspace_id=:workspaceId AND parent_id=:parentId"
                    + " AND kind='scm.file' AND id=ANY(CAST(:ids AS bigint[]))",
            nativeQuery = true)
    long countSelectedFiles(long workspaceId, long parentId, Long[] ids);

    @Query(value = """
        WITH RECURSIVE ancestry AS (SELECT id,kind,parent_id FROM item WHERE workspace_id=:workspaceId AND
        id=:itemId UNION SELECT parent.id,parent.kind,parent.parent_id FROM item parent JOIN
        ancestry child ON child.parent_id=parent.id WHERE parent.workspace_id=:workspaceId)
        SELECT id FROM ancestry WHERE kind='scm.repository'
        """, nativeQuery = true)
    @Nullable
    Long repositoryItemId(long workspaceId, long itemId);

    @Query(
            value = "SELECT id FROM item WHERE workspace_id=:workspaceId AND kind='scm.repository'"
                    + " AND source_id=:sourceId AND removed_at IS NULL",
            nativeQuery = true)
    List<Long> activeRepositoryIds(long workspaceId, long sourceId);

    @Query(
            value = "SELECT i.id FROM item i WHERE i.workspace_id=:workspaceId AND i.kind=:kind"
                    + " AND i.removed_at IS NULL ORDER BY i.id",
            nativeQuery = true)
    List<Long> scopeItems(long workspaceId, String kind, Pageable page);

    @Query(value = """
        SELECT i.id FROM item i WHERE i.workspace_id=:workspaceId AND i.kind=:kind AND i.removed_at IS NULL
        AND EXISTS(SELECT 1 FROM item_event e WHERE e.workspace_id=i.workspace_id AND e.item_id=i.id
        AND e.occurred_at>=:from AND e.occurred_at<:to) ORDER BY i.id
        """, nativeQuery = true)
    List<Long> scopeItemsInWindow(long workspaceId, String kind, Instant from, Instant to, Pageable page);

    @Query(
            value = "SELECT name,occurred_at AS occurredAt,item_version AS itemVersion,state,"
                    + "evidence_expires_at AS evidenceExpiresAt FROM item_event"
                    + " WHERE workspace_id=:workspaceId AND item_id=:itemId AND id=:eventId",
            nativeQuery = true)
    EventFact event(long workspaceId, long itemId, UUID eventId);

    interface EventFact {
        String getName();

        String getState();

        Instant getOccurredAt();

        @Nullable
        String getItemVersion();

        /** When the occasion's retained record may no longer be used, also once it is removed; null when it kept none. */
        @Nullable
        Instant getEvidenceExpiresAt();
    }

    @Query(
            value = "SELECT id,kind,source_id AS sourceId,parent_id AS parentId,path,removed_at AS removedAt"
                    + " FROM item WHERE workspace_id=:ws AND id=:id AND removed_at IS NULL",
            nativeQuery = true)
    Optional<ItemRow> activeItem(long ws, long id);

    @Query(
            value = "SELECT id,kind,source_id AS sourceId,parent_id AS parentId,path,removed_at AS removedAt"
                    + " FROM item WHERE workspace_id=:ws AND id IN (:ids)",
            nativeQuery = true)
    List<ItemRow> items(long ws, Collection<Long> ids);

    @Query(
            value = "SELECT id FROM item WHERE workspace_id=:ws AND (CAST(:kind AS text) IS NULL OR kind=:kind) ORDER"
                    + " BY id DESC",
            countQuery =
                    "SELECT count(*) FROM item WHERE workspace_id=:ws AND (CAST(:kind AS text) IS NULL OR kind=:kind)",
            nativeQuery = true)
    Page<Long> directory(long ws, @Nullable String kind, Pageable page);

    @Query(
            value = "SELECT account_id FROM membership WHERE workspace_id=:ws AND account_id IN (:ids)",
            nativeQuery = true)
    List<Long> memberAccountIds(long ws, Collection<Long> ids);

    interface ItemRow {
        long getId();

        String getKind();

        @Nullable
        Long getSourceId();

        @Nullable
        Long getParentId();

        @Nullable
        String getPath();

        @Nullable
        Instant getRemovedAt();

        default ItemRef ref(long workspaceId) {
            return new ItemRef(workspaceId, getId(), new ItemKind(getKind()), getSourceId(), getParentId(), getPath());
        }
    }
}
