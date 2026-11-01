package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for Repository entities.
 *
 * <p>This repository contains only domain-agnostic queries for the integration.scm domain.
 * Scope-filtered queries (those that join with RepositoryToMonitor or other consuming module
 * entities) belong in the consuming packages (activity, practices, etc.) to maintain
 * clean architecture boundaries.
 *
 * <p>Workspace-agnostic by design: provider-domain lookups (by native ID, full name) run
 * during sync flows that resolve the workspace later via {@code repository_to_monitor}
 * joins or {@code WorkspaceContext}. Direct entity load/save SQL is allowed by the
 * PK-only DML carve-out in {@code WorkspaceStatementInspector}.
 */
@org.springframework.stereotype.Repository
@WorkspaceAgnostic("Provider-domain lookups by native ID / full name; workspace resolved downstream")
public interface RepositoryRepository extends JpaRepository<Repository, Long> {
    /**
     * A live workspace root keeps its canonical source until the transaction ends. Shared claims coexist with
     * other workspaces and ordinary mirror updates. They never wait behind deletion: a provider transaction can
     * already hold a work row that deletion needs.
     */
    @Query(value = "SELECT id FROM repository WHERE id=:repositoryId FOR KEY SHARE NOWAIT", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> acquireIndexClaim(@Param("repositoryId") long repositoryId);

    default void claimForIndex(long repositoryId) {
        if (acquireIndexClaim(repositoryId).isEmpty()) {
            throw new ObjectOptimisticLockingFailureException(Repository.class, repositoryId);
        }
    }

    /**
     * Fences source deletion against new workspace roots. NOWAIT also avoids a cycle with a caller's removed item
     * and an in-flight event that still sees the old monitor. A refused claim rolls the whole removal back.
     */
    @Query(value = "SELECT id FROM repository WHERE id=:repositoryId FOR UPDATE NOWAIT", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> acquireDeletionClaim(@Param("repositoryId") long repositoryId);

    /**
     * Finds a repository by its full name (owner/name).
     * Used during sync operations to check if repository exists.
     */
    Optional<Repository> findByNameWithOwner(String nameWithOwner);

    @EntityGraph(attributePaths = {"organization", "provider"})
    Optional<Repository> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    @EntityGraph(attributePaths = {"organization", "provider"})
    Optional<Repository> findByNameWithOwnerAndProviderId(String nameWithOwner, Long providerId);

    /**
     * Finds a repository by ID with the organization eagerly fetched.
     * Used in backfill operations where the repository is passed across transaction boundaries.
     *
     * @param id the repository ID
     * @return the repository with organization loaded, or empty if not found
     */
    @Query("SELECT r FROM Repository r LEFT JOIN FETCH r.organization LEFT JOIN FETCH r.provider WHERE r.id = :id")
    Optional<Repository> findByIdWithOrganization(@Param("id") Long id);

    /**
     * Finds all repositories on a provider with the given prefix (owner/).
     * Used during installation operations for org login renames.
     */
    List<Repository> findByNameWithOwnerStartingWithIgnoreCaseAndProviderId(String prefix, Long providerId);

    /**
     * Finds a repository by its full name with the organization eagerly fetched.
     */
    @Query(
            "SELECT r FROM Repository r LEFT JOIN FETCH r.organization LEFT JOIN FETCH r.provider WHERE r.nameWithOwner = :nameWithOwner")
    Optional<Repository> findByNameWithOwnerWithOrganization(@Param("nameWithOwner") String nameWithOwner);

    /**
     * Updates the last sync timestamp for a repository.
     * Used after successful issue sync to enable incremental sync on subsequent runs.
     *
     * @param id         the repository ID
     * @param lastSyncAt the timestamp to set
     */
    @Transactional
    @Modifying
    @Query("UPDATE Repository r SET r.lastSyncAt = :lastSyncAt WHERE r.id = :id")
    void updateLastSyncAt(@Param("id") Long id, @Param("lastSyncAt") Instant lastSyncAt);

    /**
     * Finds all repositories belonging to a specific organization (by login) and provider.
     * Used by GitLab team sync to find repos owned by a subgroup.
     */
    List<Repository> findAllByOrganization_LoginIgnoreCaseAndProviderId(String login, Long providerId);

    /**
     * Finds all repositories that match the workspace's monitored repo names.
     * Uses nameWithOwner to join with RepositoryToMonitor entries,
     * ensuring subgroup repos are included (not just top-level group repos).
     *
     * <p>Ordered because callers scan a CAPPED prefix of the result into agent context: an arbitrary plan
     * order would vary which repositories the review sees between runs over identical work.
     */
    @Query("""
        SELECT r FROM Repository r LEFT JOIN FETCH r.provider
        WHERE EXISTS (
            SELECT 1 FROM RepositoryToMonitor m
            WHERE m.workspace.id = :workspaceId AND m.nameWithOwner = r.nameWithOwner
                AND monitor_covers_repository(m.workspace.id, m.nativeId, r.nativeId, r.provider.id) = true
        )
        ORDER BY r.nameWithOwner
        """)
    List<Repository> findAllByWorkspaceMonitors(@Param("workspaceId") Long workspaceId);

    /** Requires the workspace monitor's exact path, native identity and configured SCM provider. */
    @Query("""
        SELECT (COUNT(r) > 0) FROM Repository r
        WHERE r.id = :repositoryId AND EXISTS (
            SELECT 1 FROM RepositoryToMonitor m
            WHERE m.workspace.id = :workspaceId AND m.nameWithOwner = r.nameWithOwner
                AND monitor_covers_repository(m.workspace.id, m.nativeId, r.nativeId, r.provider.id) = true
        )
        """)
    boolean isMonitored(@Param("workspaceId") long workspaceId, @Param("repositoryId") long repositoryId);

    /** Whether any workspace still holds this exact source as an active repository item, monitored or stopped. */
    @WorkspaceAgnostic("Shared source rows outlive one workspace while another workspace still indexes them")
    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM item i WHERE i.kind='scm.repository' AND i.parent_id IS NULL
          AND i.source_id=:repositoryId AND i.removed_at IS NULL)
        """, nativeQuery = true)
    boolean isIndexedByAnyWorkspace(@Param("repositoryId") long repositoryId);

    /** Whether this workspace holds the exact source as an active repository item, monitored or stopped. */
    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM item i WHERE i.workspace_id=:workspaceId AND i.kind='scm.repository'
          AND i.parent_id IS NULL AND i.source_id=:repositoryId AND i.removed_at IS NULL)
        """, nativeQuery = true)
    boolean isIndexedByWorkspace(@Param("workspaceId") long workspaceId, @Param("repositoryId") long repositoryId);

    /** The sources this workspace holds as active repository items, including stopped GitLab projects. */
    @Query(value = """
        SELECT i.source_id FROM item i WHERE i.workspace_id=:workspaceId AND i.kind='scm.repository'
          AND i.parent_id IS NULL AND i.source_id IS NOT NULL AND i.removed_at IS NULL ORDER BY i.source_id
        """, nativeQuery = true)
    List<Long> findIndexedRepositoryIds(@Param("workspaceId") long workspaceId);

    /**
     * The repositories on {@code providerId} at the workspace's monitored paths. A monitor names a path, and another
     * instance can host the same path, so a caller that writes on the workspace's behalf passes the provider of its
     * active connection.
     */
    @Query("""
        SELECT r FROM Repository r JOIN FETCH r.provider p
        WHERE p.id = :providerId
        AND EXISTS (
            SELECT 1 FROM RepositoryToMonitor m
            WHERE m.workspace.id = :workspaceId AND m.nameWithOwner = r.nameWithOwner
                AND monitor_covers_repository(m.workspace.id, m.nativeId, r.nativeId, r.provider.id) = true
        )
        ORDER BY r.nameWithOwner
        """)
    List<Repository> findAllByWorkspaceMonitorsOnProvider(
            @Param("workspaceId") Long workspaceId, @Param("providerId") Long providerId);

    /**
     * Records a provisioning snapshot (e.g., GitHub App installation webhook) on the repository with this
     * provider-native id. The row keeps its id and the fields the full GraphQL sync owns (description,
     * pushed_at, default_branch, etc.); a rename or a case change moves its path.
     * <p>
     * Writes nothing and returns {@code false} when another repository of the provider holds the path: a
     * path names one repository per provider, so neither row is reassigned to the other.
     * <p>
     * A concurrent first insert of the same repository is absorbed by {@code ON CONFLICT DO NOTHING} and
     * the read that follows it, so NATS event processing and GraphQL sync cannot fail with an optimistic
     * locking error.
     *
     * @param nativeId       the provider's original numeric ID for the repository
     * @param providerId     the IdentityProvider entity ID
     * @param nameWithOwner  the full name (e.g., "owner/repo")
     * @param name           the short repository name
     * @param isPrivate      whether the repository is private
     * @param organizationId the organization ID (nullable)
     * @return whether the provider's repository with this id now has this path
     */
    default boolean upsertFromSnapshot(
            long nativeId,
            long providerId,
            String nameWithOwner,
            String name,
            boolean isPrivate,
            @Nullable Long organizationId) {
        return updateFromSnapshot(nativeId, providerId, nameWithOwner, name, isPrivate, organizationId) == 1
                || insertFromSnapshot(nativeId, providerId, nameWithOwner, name, isPrivate, organizationId) == 1
                || existsByNativeIdAndProviderIdAndNameWithOwner(nativeId, providerId, nameWithOwner);
    }

    /**
     * {@code visibility} is derived from {@code isPrivate} and {@code html_url} from {@code nameWithOwner}
     * inside the SQL to keep the parameter count within architecture limits.
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE repository SET
            name_with_owner = :nameWithOwner,
            name = :name,
            html_url = CASE WHEN name_with_owner = :nameWithOwner THEN html_url
                            ELSE 'https://github.com/' || :nameWithOwner END,
            is_private = :isPrivate,
            visibility = CASE WHEN :isPrivate THEN 'PRIVATE' ELSE 'PUBLIC' END,
            organization_id = COALESCE(organization_id, :organizationId)
        WHERE provider_id = :providerId AND native_id = :nativeId
          AND NOT EXISTS (SELECT 1 FROM repository holder
                          WHERE holder.provider_id = :providerId AND holder.name_with_owner = :nameWithOwner
                            AND holder.native_id <> :nativeId)
        """, nativeQuery = true)
    int updateFromSnapshot(
            @Param("nativeId") long nativeId,
            @Param("providerId") long providerId,
            @Param("nameWithOwner") String nameWithOwner,
            @Param("name") String name,
            @Param("isPrivate") boolean isPrivate,
            @Param("organizationId") @Nullable Long organizationId);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO repository (native_id, provider_id, name_with_owner, name, is_private, html_url,
                                visibility, default_branch, pushed_at, is_archived, is_disabled,
                                has_discussions_enabled, organization_id)
        VALUES (:nativeId, :providerId, :nameWithOwner, :name, :isPrivate,
                'https://github.com/' || :nameWithOwner,
                CASE WHEN :isPrivate THEN 'PRIVATE' ELSE 'PUBLIC' END,
                'main', NOW(), false, false, false, :organizationId)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int insertFromSnapshot(
            @Param("nativeId") long nativeId,
            @Param("providerId") long providerId,
            @Param("nameWithOwner") String nameWithOwner,
            @Param("name") String name,
            @Param("isPrivate") boolean isPrivate,
            @Param("organizationId") @Nullable Long organizationId);

    boolean existsByNativeIdAndProviderIdAndNameWithOwner(Long nativeId, Long providerId, String nameWithOwner);
}
