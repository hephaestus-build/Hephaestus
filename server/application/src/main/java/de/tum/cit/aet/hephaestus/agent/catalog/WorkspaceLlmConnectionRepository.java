package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceLlmConnectionRepository extends JpaRepository<WorkspaceLlmConnection, Long> {
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM WorkspaceLlmConnection c WHERE c.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<WorkspaceLlmConnection> findByWorkspaceId(Long workspaceId);

    Optional<WorkspaceLlmConnection> findByWorkspaceIdAndSlug(Long workspaceId, String slug);

    /** Tenancy-safe lookup for a client-supplied id (path variable) — never trust a bare {@code findById}. */
    Optional<WorkspaceLlmConnection> findByIdAndWorkspaceId(Long id, Long workspaceId);

    /**
     * A partial PATCH writes every column back, so without this lock a concurrent PATCH can undo one
     * that cleared the API key, leaving live a credential the admin believes they deleted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM WorkspaceLlmConnection c WHERE c.id = :id AND c.workspace.id = :workspaceId")
    Optional<WorkspaceLlmConnection> findByIdAndWorkspaceIdForUpdate(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId);

    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.catalog.SealedProbeTarget("
            + "c.baseUrl, c.authMode, c.sealedApiKey, c.apiProtocol, c.workspace.id, c.slug) "
            + "FROM WorkspaceLlmConnection c WHERE c.id = :id AND c.workspace.id = :workspaceId")
    Optional<SealedProbeTarget> findProbeTargetByIdAndWorkspaceId(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId);

    /** Every row, locked, for the system key rotation ({@code SystemKeyRotation}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @WorkspaceAgnostic("Seals the AI provider keys of every workspace again during a system key rotation")
    @Query("SELECT x FROM WorkspaceLlmConnection x ORDER BY x.id")
    List<WorkspaceLlmConnection> lockAllForKeyRotation();
}
