package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceLlmModelRepository extends JpaRepository<WorkspaceLlmModel, Long> {
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM WorkspaceLlmModel m WHERE m.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<WorkspaceLlmModel> findByWorkspaceId(Long workspaceId);

    @Query("SELECT m FROM WorkspaceLlmModel m JOIN FETCH m.connection WHERE m.workspace.id = :workspaceId ORDER BY"
            + " m.id")
    List<WorkspaceLlmModel> findByWorkspaceIdWithConnection(@Param("workspaceId") Long workspaceId);

    boolean existsByConnectionIdAndUpstreamModelId(Long connectionId, String upstreamModelId);

    Optional<WorkspaceLlmModel> findByWorkspaceIdAndSlug(Long workspaceId, String slug);

    /** The own-provider mirror of {@code LlmModelRepository#findByUpstreamModelId}, scoped to one tenant. */
    List<WorkspaceLlmModel> findByWorkspaceIdAndUpstreamModelId(Long workspaceId, String upstreamModelId);

    /** Tenancy-safe lookup for a client-supplied id (path variable) — never trust a bare {@code findById}. */
    Optional<WorkspaceLlmModel> findByIdAndWorkspaceId(Long id, Long workspaceId);

    /**
     * {@code connection} is fetched eagerly because callers map the entity to a DTO after the
     * transaction closes and OSIV is off.
     */
    @Query("SELECT m FROM WorkspaceLlmModel m JOIN FETCH m.connection WHERE m.id = :id AND m.workspace.id ="
            + " :workspaceId")
    Optional<WorkspaceLlmModel> findByIdAndWorkspaceIdWithConnection(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId);

    /** Serializes the workspace model's combined activation and price replacement. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM WorkspaceLlmModel m JOIN FETCH m.connection "
            + "WHERE m.id = :id AND m.workspace.id = :workspaceId")
    Optional<WorkspaceLlmModel> findByIdAndWorkspaceIdForUpdate(
            @Param("id") Long id, @Param("workspaceId") Long workspaceId);

    boolean existsByConnectionIdAndWorkspaceId(Long connectionId, Long workspaceId);

    @Query("SELECT m FROM WorkspaceLlmModel m JOIN FETCH m.connection c "
            + "WHERE m.workspace.id = :workspaceId AND m.enabled = true AND c.enabled = true ORDER BY m.id")
    List<WorkspaceLlmModel> findEnabledWithEnabledConnection(@Param("workspaceId") Long workspaceId);

    @WorkspaceAgnostic("Instance-admin pool editor lists every workspace's own models")
    @Query("SELECT m FROM WorkspaceLlmModel m JOIN FETCH m.connection JOIN FETCH m.workspace ORDER BY m.id")
    List<WorkspaceLlmModel> findAllForCallPools();

    @WorkspaceAgnostic("Instance-admin pool editor removes the pool's models, in any workspace")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WorkspaceLlmModel m SET m.callPoolId = NULL WHERE m.callPoolId = :poolId")
    int leaveCallPool(@Param("poolId") Long poolId);

    @WorkspaceAgnostic("Instance-admin pool editor puts models of any workspace into a pool")
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WorkspaceLlmModel m SET m.callPoolId = :poolId WHERE m.id IN :ids")
    int joinCallPool(@Param("poolId") Long poolId, @Param("ids") Collection<Long> ids);
}
