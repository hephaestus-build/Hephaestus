package de.tum.cit.aet.hephaestus.integration.scm.domain.repository;

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
     * Finds a repository by its full name (owner/name).
     * Used during sync operations to check if repository exists.
     */
    Optional<Repository> findByNameWithOwner(String nameWithOwner);

    Optional<Repository> findByNativeIdAndProviderId(Long nativeId, Long providerId);

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
     * Finds all repositories with the given prefix (owner/).
     * Used during installation operations for org login renames.
     */
    List<Repository> findByNameWithOwnerStartingWithIgnoreCase(String prefix);

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
     * Upsert a repository from a provisioning snapshot (e.g., GitHub App installation webhook).
     * <p>
     * Uses PostgreSQL's {@code ON CONFLICT} on the {@code (provider_id, name_with_owner)} unique
     * constraint to atomically insert or update, eliminating race conditions between concurrent
     * NATS event processing and GraphQL sync that previously caused optimistic locking errors.
     * <p>
     * On conflict, only lightweight fields from the snapshot are updated; fields populated by
     * the full GraphQL sync (description, pushed_at, default_branch, etc.) are preserved.
     * <p>
     * {@code visibility} is derived from {@code isPrivate} and {@code html_url} is derived
     * from {@code nameWithOwner} (with a {@code https://github.com/} prefix) inside the SQL
     * to keep the parameter count within architecture limits.
     *
     * @param nativeId       the provider's original numeric ID for the repository
     * @param providerId     the IdentityProvider entity ID
     * @param nameWithOwner  the full name (e.g., "owner/repo")
     * @param name           the short repository name
     * @param isPrivate      whether the repository is private
     * @param organizationId the organization ID (nullable)
     */
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
     * order would vary which repositories the detector sees between runs over identical work.
     */
    @Query("""
        SELECT r FROM Repository r LEFT JOIN FETCH r.provider
        WHERE r.nameWithOwner IN (
            SELECT m.nameWithOwner FROM RepositoryToMonitor m WHERE m.workspace.id = :workspaceId
        )
        ORDER BY r.nameWithOwner
        """)
    List<Repository> findAllByWorkspaceMonitors(@Param("workspaceId") Long workspaceId);

    /**
     * The repositories on {@code providerId} at the workspace's monitored paths. A monitor names a path, and another
     * instance can host the same path, so a caller that writes on the workspace's behalf passes the provider of its
     * active connection.
     */
    @Query("""
        SELECT r FROM Repository r JOIN FETCH r.provider p
        WHERE p.id = :providerId
        AND r.nameWithOwner IN (
            SELECT m.nameWithOwner FROM RepositoryToMonitor m WHERE m.workspace.id = :workspaceId
        )
        ORDER BY r.nameWithOwner
        """)
    List<Repository> findAllByWorkspaceMonitorsOnProvider(
            @Param("workspaceId") Long workspaceId, @Param("providerId") Long providerId);

    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO repository (native_id, provider_id, name_with_owner, name, is_private, html_url,
                                visibility, default_branch, pushed_at, is_archived, is_disabled,
                                has_discussions_enabled, organization_id)
        VALUES (:nativeId, :providerId, :nameWithOwner, :name, :isPrivate,
                'https://github.com/' || :nameWithOwner,
                CASE WHEN :isPrivate THEN 'PRIVATE' ELSE 'PUBLIC' END,
                'main', NOW(), false, false, false, :organizationId)
        ON CONFLICT (provider_id, name_with_owner) DO UPDATE SET
            name = EXCLUDED.name,
            is_private = EXCLUDED.is_private,
            visibility = EXCLUDED.visibility,
            organization_id = COALESCE(repository.organization_id, EXCLUDED.organization_id)
        """, nativeQuery = true)
    void upsertFromSnapshot(
            @Param("nativeId") long nativeId,
            @Param("providerId") long providerId,
            @Param("nameWithOwner") String nameWithOwner,
            @Param("name") String name,
            @Param("isPrivate") boolean isPrivate,
            @Param("organizationId") @Nullable Long organizationId);
}
