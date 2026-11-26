package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
@WorkspaceAgnostic("SCM mirror rows are shared across workspaces; callers validate the provider or monitored scope")
public interface RepositoryRepository extends JpaRepository<Repository, Long> {
    /** Every workspace's repositories. Only tests call it, to check what sync stored; production code reads by scope. */
    @Override
    @WorkspaceAgnostic("Reads every workspace's mirror rows; only tests call it, to check what sync stored")
    long count();

    /** Deletes every workspace's repositories. Only tests call it, to start from an empty mirror. */
    @Override
    @WorkspaceAgnostic("Deletes every workspace's mirror rows; only tests call it, to start from an empty mirror")
    void deleteAll();

    /**
     * A live workspace root keeps its canonical source until the transaction ends. Shared claims coexist with
     * other workspaces and ordinary mirror updates. They never wait behind deletion: a provider transaction can
     * already hold a work row that deletion needs.
     */
    @Query(value = "SELECT r.id FROM repository r WHERE r.id=:repositoryId FOR KEY SHARE NOWAIT", nativeQuery = true)
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
    @Query(value = "SELECT r.id FROM repository r WHERE r.id=:repositoryId FOR UPDATE NOWAIT", nativeQuery = true)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Long> acquireDeletionClaim(@Param("repositoryId") long repositoryId);

    /** A repository by its full name on any provider. Only tests call it; production code also names the provider. */
    @WorkspaceAgnostic(
            "Reads a shared mirror row by name on any provider; only tests call it, to check what sync stored")
    Optional<Repository> findByNameWithOwner(String nameWithOwner);

    /** GitHub mirrors of a repository name; a GitLab project on the same path is another repository. */
    @WorkspaceAgnostic("Reads shared mirror rows by name, so an instance-wide exclusion finds every workspace's copy")
    @Query(value = """
            SELECT r.id FROM repository r JOIN identity_provider p ON p.id = r.provider_id
            WHERE LOWER(r.name_with_owner) = LOWER(:nameWithOwner) AND p.type = 'GITHUB'
            """, nativeQuery = true)
    List<Long> findGitHubIdsByNameIgnoringCase(@Param("nameWithOwner") String nameWithOwner);

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
    List<Repository> findByNameWithOwnerStartingWithIgnoreCaseAndProviderIdOrderByNativeIdAsc(
            String prefix, Long providerId);

    /**
     * Updates the last sync timestamp for a repository.
     * Used after successful issue sync to enable incremental sync on subsequent runs.
     *
     * @param id         the repository ID
     * @param lastSyncAt the timestamp to set
     */
    @WorkspaceAgnostic("GitLab sync stamps the shared mirror row of a repository it has just synced")
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

    /** The ids of the repositories that {@link #isMonitored} accepts for this workspace. */
    @Query("""
        SELECT r.id FROM Repository r
        WHERE EXISTS (
            SELECT 1 FROM RepositoryToMonitor m
            WHERE m.workspace.id = :workspaceId AND m.nameWithOwner = r.nameWithOwner
                AND monitor_covers_repository(m.workspace.id, m.nativeId, r.nativeId, r.provider.id) = true
        )
        """)
    Set<Long> findMonitoredRepositoryIds(@Param("workspaceId") long workspaceId);

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
}
