package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemEventRepository extends JpaRepository<ItemEvent, UUID> {
    /** Only a kept record expires; a statement that none was kept holds no words and names no person. */
    String RECORD_EXPIRY = "CASE WHEN CAST(:evidence AS jsonb)->'unretained' IS NULL "
            + "THEN CAST(:evidenceExpiresAt AS timestamptz) END";

    Optional<ItemEvent> findByIdAndWorkspaceId(UUID id, long workspaceId);

    Optional<ItemEvent> findByWorkspaceIdAndItemIdAndNameAndItemVersion(
            long workspaceId, long itemId, String name, String itemVersion);

    Optional<ItemEvent> findByWorkspaceIdAndItemIdAndNameAndSourceKey(
            long workspaceId, long itemId, String name, String sourceKey);

    @Modifying
    @Query(
            value =
                    "INSERT INTO item_event(id, workspace_id, item_id, name, item_version, source_key, occurred_at, found_by, actor_user_id, requested_by_id, state, decided_at, evidence, evidence_expires_at) VALUES (:id, :workspaceId, :itemId, :name, :itemVersion, :sourceKey, :occurredAt, :foundBy, :actorUserId, :requestedById, 'RECORDED', :now, CAST(:evidence AS jsonb), "
                            + RECORD_EXPIRY + ") ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("name") String name,
            @Param("itemVersion") String itemVersion,
            @Param("sourceKey") @Nullable String sourceKey,
            @Param("occurredAt") Instant occurredAt,
            @Param("foundBy") String foundBy,
            @Param("actorUserId") @Nullable Long actorUserId,
            @Param("requestedById") @Nullable Long requestedById,
            @Param("now") Instant now,
            @Param("evidence") @Nullable String evidence,
            @Param("evidenceExpiresAt") @Nullable Instant evidenceExpiresAt);

    /** Records an occasion with no pinned version once per name and fence; the caller holds the item lock. */
    @Modifying
    @Query(
            value =
                    "INSERT INTO item_event(id, workspace_id, item_id, name, item_version, pending_fence, source_key, occurred_at, found_by, actor_user_id, state, decided_at, evidence, evidence_expires_at) "
                            + "SELECT :id, :workspaceId, :itemId, :name, NULL, :fence, :sourceKey, :occurredAt, :foundBy, :actorUserId, 'RECORDED', :now, CAST(:evidence AS jsonb), "
                            + RECORD_EXPIRY + " "
                            + "WHERE NOT EXISTS (SELECT 1 FROM item_event e WHERE e.workspace_id = :workspaceId AND e.item_id = :itemId AND e.name = :name "
                            + "AND e.item_version IS NULL AND e.pending_fence = :fence) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertUnresolvedIfAbsent(
            @Param("id") UUID id,
            @Param("workspaceId") long workspaceId,
            @Param("itemId") long itemId,
            @Param("name") String name,
            @Param("fence") String fence,
            @Param("sourceKey") @Nullable String sourceKey,
            @Param("occurredAt") Instant occurredAt,
            @Param("foundBy") String foundBy,
            @Param("actorUserId") @Nullable Long actorUserId,
            @Param("now") Instant now,
            @Param("evidence") @Nullable String evidence,
            @Param("evidenceExpiresAt") @Nullable Instant evidenceExpiresAt);

    /**
     * The evidence of exactly this occasion of this item: its record, or the reason it keeps none, with its expiry.
     * Any other workspace, item or event reads nothing.
     */
    @Query(
            value = "SELECT " + EVIDENCE_COLUMNS
                    + " FROM item_event WHERE workspace_id = :workspaceId AND item_id = :itemId AND id = :id",
            nativeQuery = true)
    Optional<EvidenceRow> findEvidence(
            @Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("id") UUID id);

    String EVIDENCE_COLUMNS = "item_version AS itemVersion, evidence IS NOT NULL AS retained, "
            + "CASE WHEN evidence->'unretained' IS NULL THEN CAST(evidence AS text) END AS evidence, "
            + "evidence->>'unretained' AS unretained, evidence_expires_at AS evidenceExpiresAt";

    interface EvidenceRow {
        @Nullable
        String getItemVersion();

        /** False for an occasion recorded before its source retained evidence, or by a source that retains none. */
        boolean getRetained();

        @Nullable
        String getEvidence();

        @Nullable
        String getUnretained();

        @Nullable
        Instant getEvidenceExpiresAt();
    }

    /**
     * Replaces the retained records on an item by a statement of why they are gone; the occasions stay. Each keeps its
     * fixed expiry, which names no person, so no copy made from the record outlives it.
     */
    @Modifying
    @Query(
            value =
                    "UPDATE item_event SET evidence = jsonb_build_object('unretained', CAST(:reason AS text)) "
                            + "WHERE workspace_id = :workspaceId AND item_id = :itemId AND evidence IS NOT NULL AND evidence->'unretained' IS NULL",
            nativeQuery = true)
    int forgetItemEvidence(
            @Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("reason") String reason);

    @Modifying
    @Query(
            value =
                    "UPDATE item_event SET evidence = jsonb_build_object('unretained', CAST(:reason AS text)) "
                            + "WHERE workspace_id = :workspaceId AND name LIKE :prefix AND evidence IS NOT NULL AND evidence->'unretained' IS NULL",
            nativeQuery = true)
    int forgetWorkspaceEvidence(
            @Param("workspaceId") long workspaceId, @Param("prefix") String prefix, @Param("reason") String reason);

    /**
     * Replaces expired retained records by a statement that they expired, also on an occasion a review still
     * references; the occasion itself stays with its expiry. Only records that still keep words are selected, so a
     * statement is never rewritten.
     */
    @Modifying
    @Query(
            value = "UPDATE item_event SET evidence = jsonb_build_object('unretained', 'EXPIRED') "
                    + "WHERE id IN (SELECT e.id FROM item_event e WHERE e.evidence_expires_at <= :now "
                    + "AND e.evidence IS NOT NULL AND e.evidence->'unretained' IS NULL "
                    + "ORDER BY e.evidence_expires_at, e.id LIMIT :limit FOR UPDATE SKIP LOCKED)",
            nativeQuery = true)
    @WorkspaceAgnostic("Background reaper removes expired evidence across workspaces; each row keeps its workspace")
    int forgetExpiredEvidence(@Param("now") Instant now, @Param("limit") int limit);

    Optional<ItemEvent> findByWorkspaceIdAndItemIdAndNameAndItemVersionIsNullAndPendingFence(
            long workspaceId, long itemId, String name, String pendingFence);

    /** Metadata of the open occasions without a version; their retained records are not read here. */
    @Query(
            value =
                    "SELECT id, name, state, pending_fence AS pendingFence FROM item_event WHERE workspace_id = :workspaceId AND item_id = :itemId "
                            + "AND item_version IS NULL AND state IN ('RECORDED','WAITING') ORDER BY occurred_at, id",
            nativeQuery = true)
    List<UnresolvedRow> findUnresolved(@Param("workspaceId") long workspaceId, @Param("itemId") long itemId);

    interface UnresolvedRow {
        UUID getId();

        String getName();

        String getState();

        @Nullable
        String getPendingFence();
    }

    /**
     * Pins the version and replaces retained evidence with its completed record. Null and durable removal statements
     * survive even when completion read an earlier record; the expiry never moves.
     */
    @Modifying
    @Query(
            value =
                    "UPDATE item_event SET item_version = :itemVersion, "
                            + "evidence = CASE WHEN evidence IS NULL OR evidence -> 'unretained' IS NOT NULL "
                            + "THEN evidence ELSE CAST(:evidence AS jsonb) END "
                            + "WHERE workspace_id = :workspaceId AND id = :id AND item_version IS NULL AND state IN ('RECORDED','WAITING')",
            nativeQuery = true)
    int pin(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("itemVersion") String itemVersion,
            @Param("evidence") @Nullable String evidence);

    /** The retained record of an occasion that waits for its version, as stored, with its expiry. */
    @Query(
            value = "SELECT " + EVIDENCE_COLUMNS
                    + " FROM item_event WHERE workspace_id = :workspaceId AND id = :id AND item_version IS NULL",
            nativeQuery = true)
    Optional<EvidenceRow> findPendingEvidence(@Param("workspaceId") long workspaceId, @Param("id") UUID id);

    // An occasion with no pinned version waits for the source to store one, not for a retry. A retry offers the
    // occasion by its key; the claim reads no retained record.
    @Query(
            value =
                    "SELECT id, workspace_id AS workspaceId, item_id AS itemId FROM item_event WHERE state IN ('RECORDED','WAITING') AND item_version IS NOT NULL AND (retry_at IS NULL OR retry_at <= :now) ORDER BY occurred_at, id FOR UPDATE SKIP LOCKED LIMIT :limit",
            nativeQuery = true)
    @WorkspaceAgnostic("Background reaper claims due events across workspaces and preserves each event workspace")
    List<DueEvent> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    interface DueEvent {
        UUID getId();

        long getWorkspaceId();

        long getItemId();
    }

    record Decision(
            ItemEventState from,
            ItemEventState to,
            @Nullable ReviewRequestReason reason,
            Instant at,
            @Nullable Instant retryAt) {}

    @Modifying
    @Query("UPDATE ItemEvent e SET e.state = :#{#decision.to()}, e.reason = :#{#decision.reason()}, "
            + "e.decidedAt = :#{#decision.at()}, e.retryAt = :#{#decision.retryAt()} "
            + "WHERE e.workspaceId = :workspaceId AND e.id = :id AND e.state = :#{#decision.from()}")
    int decide(@Param("workspaceId") long workspaceId, @Param("id") UUID id, @Param("decision") Decision decision);

    @Modifying
    @Query(
            "UPDATE ItemEvent e SET e.lastAttemptedAt = :now, e.retryAt = :retryAt WHERE e.workspaceId = :workspaceId AND e.id = :id AND e.state IN (de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState.RECORDED, de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState.WAITING)")
    int attempted(
            @Param("workspaceId") long workspaceId,
            @Param("id") UUID id,
            @Param("now") Instant now,
            @Param("retryAt") Instant retryAt);

    @Modifying
    @Query(
            "UPDATE ItemEvent e SET e.state = de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState.EXPIRED, e.reason = :reason, e.decidedAt = :now, e.retryAt = NULL WHERE e.state IN (de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState.RECORDED, de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState.WAITING) AND e.occurredAt < :before")
    @WorkspaceAgnostic("Background reaper expires pending events across workspaces at the configured deadline")
    int expire(@Param("before") Instant before, @Param("now") Instant now, @Param("reason") ReviewRequestReason reason);

    @Modifying
    @Query(
            value =
                    "DELETE FROM item_event WHERE id IN (SELECT e.id FROM item_event e WHERE e.state IN ('STARTED','WITHHELD','EXPIRED') AND e.decided_at < :before AND NOT EXISTS (SELECT 1 FROM review r WHERE r.workspace_id=e.workspace_id AND r.event_id=e.id) ORDER BY e.decided_at,e.id LIMIT :limit)",
            nativeQuery = true)
    @WorkspaceAgnostic("Background reaper removes only retained terminal events without a review reference")
    int deleteUnreferenced(@Param("before") Instant before, @Param("limit") int limit);
}
