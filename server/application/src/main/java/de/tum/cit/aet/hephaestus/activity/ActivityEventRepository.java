package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Write and maintenance access to the activity ledger; the read model lives in {@code activity.overview}. */
@Repository
public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID> {
    /**
     * Atomically inserts an activity event if absent.
     *
     * <p>ON CONFLICT DO NOTHING avoids the race where exists() passes but save() fails
     * with DataIntegrityViolationException at commit.
     *
     * @return 1 if inserted, 0 if duplicate (conflict on workspace_id + event_key)
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO activity_event (
            id, event_key, event_type, occurred_at, actor_id,
            workspace_id, repository_id, target_type, target_id, ingested_at
        )
        VALUES (
            :id, :eventKey, :eventType, :occurredAt, :actorId,
            :workspaceId, :repositoryId, :targetType, :targetId, CURRENT_TIMESTAMP
        )
        ON CONFLICT (workspace_id, event_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventKey") String eventKey,
            @Param("eventType") String eventType,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorId") @Nullable Long actorId,
            @Param("workspaceId") Long workspaceId,
            @Param("repositoryId") @Nullable Long repositoryId,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId);

    /**
     * Backfills {@code actor_id} for COMMIT_CREATED events whose actor was unresolved at ingest.
     * Without this, commits ingested before their GitLab authors are resolved via email match
     * stay unattributed.
     */
    @WorkspaceAgnostic("Scoped by repository_id (repository belongs to one workspace)")
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        UPDATE activity_event
        SET actor_id = gc.author_id
        FROM git_commit gc
        WHERE activity_event.target_type = 'commit'
          AND activity_event.event_type = 'COMMIT_CREATED'
          AND activity_event.actor_id IS NULL
          AND activity_event.target_id = gc.id
          AND gc.author_id IS NOT NULL
          AND gc.repository_id = :repositoryId
        """, nativeQuery = true)
    int backfillCommitActors(@Param("repositoryId") Long repositoryId);

    @Query(value = "SELECT COUNT(*) FROM activity_event WHERE workspace_id = :workspaceId", nativeQuery = true)
    long countByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM activity_event WHERE workspace_id = :workspaceId", nativeQuery = true)
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
