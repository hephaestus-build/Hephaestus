package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the per-purpose {@link WorkspaceAgentBinding}s of a workspace. */
public interface WorkspaceAgentBindingRepository extends JpaRepository<WorkspaceAgentBinding, Long> {
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM WorkspaceAgentBinding b WHERE b.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);

    List<WorkspaceAgentBinding> findByWorkspaceId(Long workspaceId);

    /**
     * Every binding of a workspace with its model → connection graph fetched, so a caller can resolve
     * readiness after the loading transaction has closed instead of hitting a lazy-init failure.
     */
    @Query("SELECT b FROM WorkspaceAgentBinding b "
            + "LEFT JOIN FETCH b.instanceModel im LEFT JOIN FETCH im.connection "
            + "LEFT JOIN FETCH b.workspaceModel wm LEFT JOIN FETCH wm.connection "
            + "WHERE b.workspace.id = :workspaceId")
    List<WorkspaceAgentBinding> findByWorkspaceIdWithModels(@Param("workspaceId") Long workspaceId);

    /** Every slot of one purpose, model graph fetched, for the router to pick within a ceiling. */
    @Query("SELECT b FROM WorkspaceAgentBinding b LEFT JOIN FETCH b.instanceModel im LEFT JOIN FETCH im.connection "
            + "LEFT JOIN FETCH b.workspaceModel wm LEFT JOIN FETCH wm.connection "
            + "WHERE b.workspace.id = :workspaceId AND b.purpose = :purpose")
    List<WorkspaceAgentBinding> findByWorkspaceIdAndPurpose(Long workspaceId, AgentPurpose purpose);

    @Query("SELECT b FROM WorkspaceAgentBinding b LEFT JOIN FETCH b.instanceModel im LEFT JOIN FETCH im.connection "
            + "LEFT JOIN FETCH b.workspaceModel wm LEFT JOIN FETCH wm.connection "
            + "WHERE b.workspace.id = :workspaceId AND b.purpose = :purpose AND b.dataHandlingTier = :tier")
    Optional<WorkspaceAgentBinding> findByWorkspaceIdAndPurposeAndDataHandlingTier(
            Long workspaceId, AgentPurpose purpose, DataHandlingTier tier);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM WorkspaceAgentBinding b WHERE b.workspace.id = :workspaceId AND b.id = :id")
    Optional<WorkspaceAgentBinding> findByWorkspaceIdAndIdForUpdate(Long workspaceId, Long id);

    @WorkspaceAgnostic("Instance-admin delete guard: a catalog model is in use if ANY workspace binds it")
    boolean existsByInstanceModelId(Long instanceModelId);

    boolean existsByWorkspaceModelIdAndWorkspaceId(Long workspaceModelId, Long workspaceId);
}
