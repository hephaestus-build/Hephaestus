package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for Project entities.
 */
@WorkspaceAgnostic("Projects scoped through owner_id (organization/user) -> workspace context")
public interface ProjectRepository extends JpaRepository<Project, Long> {
    /**
     * Finds a project by owner type, owner ID, and project number.
     * This is the canonical lookup using the unique constraint fields.
     *
     * @param ownerType the owner type (REPOSITORY, ORGANIZATION, USER)
     * @param ownerId the owner's ID
     * @param number the project number within the owner context
     * @return the project if found
     */
    @Query("""
        SELECT p
        FROM Project p
        LEFT JOIN FETCH p.creator
        WHERE p.ownerType = :ownerType AND p.ownerId = :ownerId AND p.number = :number
        """)
    Optional<Project> findByOwnerTypeAndOwnerIdAndNumber(
            @Param("ownerType") Project.OwnerType ownerType,
            @Param("ownerId") Long ownerId,
            @Param("number") int number);

    /**
     * Checks if a project exists by owner type, owner ID, and project number.
     *
     * @param ownerType the owner type
     * @param ownerId the owner's ID
     * @param number the project number
     * @return true if the project exists
     */
    boolean existsByOwnerTypeAndOwnerIdAndNumber(Project.OwnerType ownerType, Long ownerId, int number);

    /**
     * Finds all projects for a given owner.
     *
     * @param ownerType the owner type
     * @param ownerId the owner's ID
     * @return list of projects for the owner
     */
    List<Project> findAllByOwnerTypeAndOwnerId(Project.OwnerType ownerType, Long ownerId);

    /**
     * Finds a project by its GitHub node ID.
     *
     * @param nodeId the GitHub GraphQL node ID
     * @return the project if found
     */
    Optional<Project> findByNodeId(String nodeId);

    /**
     * Atomically inserts or updates a project's core fields (race-condition safe).
     * <p>
     * Uses PostgreSQL's ON CONFLICT to handle concurrent inserts on the unique constraint
     * (owner_type, owner_id, number). This eliminates the race condition where two threads
     * both pass the findById check and try to insert the same project.
     *
     * @return 1 if inserted, 1 if updated (always 1 on success due to DO UPDATE)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO project (
            native_id, provider_id, node_id, owner_type, owner_id, number, title, short_description,
            readme, template, url, closed, closed_at, is_public, creator_id, last_sync_at,
            created_at, updated_at
        )
        VALUES (
            :nativeId, :providerId, :nodeId, :ownerType, :ownerId, :number, :title, :shortDescription,
            :readme, :template, :url, :closed, :closedAt, :isPublic, :creatorId, :lastSyncAt,
            :createdAt, :updatedAt
        )
        ON CONFLICT (owner_type, owner_id, number) DO UPDATE SET
            node_id = COALESCE(EXCLUDED.node_id, project.node_id),
            title = EXCLUDED.title,
            short_description = EXCLUDED.short_description,
            readme = EXCLUDED.readme,
            template = EXCLUDED.template,
            url = EXCLUDED.url,
            closed = EXCLUDED.closed,
            closed_at = EXCLUDED.closed_at,
            is_public = EXCLUDED.is_public,
            creator_id = COALESCE(EXCLUDED.creator_id, project.creator_id),
            last_sync_at = EXCLUDED.last_sync_at,
            created_at = COALESCE(project.created_at, EXCLUDED.created_at),
            updated_at = EXCLUDED.updated_at
        """, nativeQuery = true)
    int upsertCore(
            @Param("nativeId") Long nativeId,
            @Param("providerId") Long providerId,
            @Param("nodeId") String nodeId,
            @Param("ownerType") String ownerType,
            @Param("ownerId") Long ownerId,
            @Param("number") int number,
            @Param("title") String title,
            @Param("shortDescription") @Nullable String shortDescription,
            @Param("readme") @Nullable String readme,
            @Param("template") boolean template,
            @Param("url") @Nullable String url,
            @Param("closed") boolean closed,
            @Param("closedAt") @Nullable Instant closedAt,
            @Param("isPublic") boolean isPublic,
            @Param("creatorId") @Nullable Long creatorId,
            @Param("lastSyncAt") Instant lastSyncAt,
            @Param("createdAt") @Nullable Instant createdAt,
            @Param("updatedAt") @Nullable Instant updatedAt);

    /**
     * Updates the item sync cursor for a project.
     * <p>
     * Used to persist pagination state for resumable item sync.
     * Call this after each successfully processed page of items.
     *
     * @param projectId the project ID
     * @param cursor the pagination cursor (null to clear)
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.itemSyncCursor = :cursor WHERE p.id = :projectId")
    void updateItemSyncCursor(@Param("projectId") Long projectId, @Param("cursor") String cursor);

    /**
     * Updates the items synced timestamp for a project.
     * <p>
     * Call this when item sync completes successfully for incremental sync support.
     *
     * @param projectId the project ID
     * @param syncedAt the timestamp of successful sync
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.itemsSyncedAt = :syncedAt, p.itemSyncCursor = NULL WHERE p.id = :projectId")
    void updateItemsSyncedAt(@Param("projectId") Long projectId, @Param("syncedAt") Instant syncedAt);

    /**
     * Clears the item sync cursor for a project.
     * <p>
     * Call this when item sync completes successfully.
     *
     * @param projectId the project ID
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.itemSyncCursor = NULL WHERE p.id = :projectId")
    void clearItemSyncCursor(@Param("projectId") Long projectId);

    // Field Sync Cursor Management

    /**
     * Updates the field sync cursor for a project.
     * <p>
     * Used to persist pagination state for resumable field sync.
     * Call this after each successfully processed page of fields.
     *
     * @param projectId the project ID
     * @param cursor the pagination cursor (null to clear)
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.fieldSyncCursor = :cursor WHERE p.id = :projectId")
    void updateFieldSyncCursor(@Param("projectId") Long projectId, @Param("cursor") String cursor);

    /**
     * Updates the fields synced timestamp for a project.
     * <p>
     * Call this when field sync completes successfully. Also clears the cursor.
     *
     * @param projectId the project ID
     * @param syncedAt the timestamp of successful sync
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.fieldsSyncedAt = :syncedAt, p.fieldSyncCursor = NULL WHERE p.id = :projectId")
    void updateFieldsSyncedAt(@Param("projectId") Long projectId, @Param("syncedAt") Instant syncedAt);

    /**
     * Clears the field sync cursor for a project.
     * <p>
     * Call this when field sync completes successfully.
     *
     * @param projectId the project ID
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.fieldSyncCursor = NULL WHERE p.id = :projectId")
    void clearFieldSyncCursor(@Param("projectId") Long projectId);

    // Status Update Sync Cursor Management

    /**
     * Updates the status update sync cursor for a project.
     * <p>
     * Used to persist pagination state for resumable status update sync.
     * Call this after each successfully processed page of status updates.
     *
     * @param projectId the project ID
     * @param cursor the pagination cursor (null to clear)
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.statusUpdateSyncCursor = :cursor WHERE p.id = :projectId")
    void updateStatusUpdateSyncCursor(@Param("projectId") Long projectId, @Param("cursor") String cursor);

    /**
     * Updates the status updates synced timestamp for a project.
     * <p>
     * Call this when status update sync completes successfully. Also clears the cursor.
     *
     * @param projectId the project ID
     * @param syncedAt the timestamp of successful sync
     */
    @Modifying
    @Transactional
    @Query(
            "UPDATE Project p SET p.statusUpdatesSyncedAt = :syncedAt, p.statusUpdateSyncCursor = NULL WHERE p.id = :projectId")
    void updateStatusUpdatesSyncedAt(@Param("projectId") Long projectId, @Param("syncedAt") Instant syncedAt);

    /**
     * Clears the status update sync cursor for a project.
     * <p>
     * Call this when status update sync completes successfully.
     *
     * @param projectId the project ID
     */
    @Modifying
    @Transactional
    @Query("UPDATE Project p SET p.statusUpdateSyncCursor = NULL WHERE p.id = :projectId")
    void clearStatusUpdateSyncCursor(@Param("projectId") Long projectId);

    /**
     * Finds projects for an organization that need item sync, respecting cooldown.
     * <p>
     * Returns projects ordered by last sync time (oldest first) to prioritize
     * projects that haven't been synced recently. Only includes projects where:
     * <ul>
     *   <li>itemsSyncedAt is null (never synced), OR</li>
     *   <li>itemsSyncedAt is before the cooldown threshold</li>
     * </ul>
     * <p>
     * This mirrors the repository sync behavior in {@code GitHubDataSyncService.shouldSync()}.
     *
     * @param ownerId           the organization ID
     * @param cooldownThreshold projects synced after this time are skipped
     * @return list of projects that need item sync
     */
    @Query("""
        SELECT p FROM Project p
        WHERE p.ownerType = :ownerType AND p.ownerId = :ownerId
          AND (p.itemsSyncedAt IS NULL OR p.itemsSyncedAt < :cooldownThreshold)
        ORDER BY COALESCE(p.itemsSyncedAt, p.createdAt) ASC
        """)
    List<Project> findProjectsNeedingItemSync(
            @Param("ownerType") Project.OwnerType ownerType,
            @Param("ownerId") Long ownerId,
            @Param("cooldownThreshold") Instant cooldownThreshold);

    // Cascade Delete Operations
    // These methods support application-level referential integrity for the
    // polymorphic owner relationship (ownerType + ownerId).
    //
    // DESIGN NOTE: True database-level FK constraints are not feasible because
    // ownerId can reference Organization, Repository, or User tables depending
    // on ownerType. This polymorphic ownership pattern is intentional to support
    // GitHub's flexible project ownership model.
    //
    // Instead, we implement cascade deletes at the application level via these
    // repository methods + event listeners. See ProjectIntegrityService for the
    // coordinating service.

    /**
     * Deletes all projects owned by a specific owner.
     * <p>
     * This is used for cascade delete when an owner entity (Organization, Repository,
     * or User) is deleted. The cascade also removes all related entities (items,
     * fields, status updates) due to JPA cascade settings on the Project entity.
     *
     * @param ownerType the owner type (ORGANIZATION, REPOSITORY, USER)
     * @param ownerId   the owner's database ID
     * @return the number of projects deleted
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Project p WHERE p.ownerType = :ownerType AND p.ownerId = :ownerId")
    int deleteAllByOwnerTypeAndOwnerId(@Param("ownerType") Project.OwnerType ownerType, @Param("ownerId") Long ownerId);

    /**
     * Counts projects owned by a specific owner.
     * <p>
     * Useful for logging/metrics before cascade delete operations.
     *
     * @param ownerType the owner type
     * @param ownerId   the owner's database ID
     * @return the count of projects
     */
    long countByOwnerTypeAndOwnerId(Project.OwnerType ownerType, Long ownerId);

    /**
     * Checks if any projects exist for a specific owner.
     * <p>
     * More efficient than count when you only need existence check.
     *
     * @param ownerType the owner type
     * @param ownerId   the owner's database ID
     * @return true if at least one project exists for this owner
     */
    boolean existsByOwnerTypeAndOwnerId(Project.OwnerType ownerType, Long ownerId);

    /**
     * Finds all orphaned projects (projects whose owner entity no longer exists).
     * <p>
     * Uses LEFT JOINs against all owner tables to detect missing owners in a single query,
     * avoiding the N+1 problem of checking each project individually.
     *
     * @return list of orphaned projects
     */
    @Query(value = """
        SELECT p.* FROM project p
        LEFT JOIN organization o ON p.owner_type = 'ORGANIZATION' AND p.owner_id = o.id
        LEFT JOIN repository r ON p.owner_type = 'REPOSITORY' AND p.owner_id = r.id
        LEFT JOIN "user" u ON p.owner_type = 'USER' AND p.owner_id = u.id
        WHERE (p.owner_type = 'ORGANIZATION' AND o.id IS NULL)
           OR (p.owner_type = 'REPOSITORY' AND r.id IS NULL)
           OR (p.owner_type = 'USER' AND u.id IS NULL)
        """, nativeQuery = true)
    List<Project> findOrphanedProjects();
}
