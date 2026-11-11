package de.tum.cit.aet.hephaestus.integration.scm.workspace.settings;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@WorkspaceAgnostic("Queried by explicit workspace ID - settings queries always workspace-scoped")
public interface WorkspaceTeamSettingsRepository
        extends JpaRepository<WorkspaceTeamSettings, WorkspaceTeamSettings.Id> {
    List<WorkspaceTeamSettings> findByWorkspaceId(Long workspaceId);

    Optional<WorkspaceTeamSettings> findByWorkspaceIdAndTeamId(Long workspaceId, Long teamId);

    @Query("""
        SELECT wts.team.id FROM WorkspaceTeamSettings wts
        WHERE wts.workspace.id = :workspaceId AND wts.hidden = true
        """)
    Set<Long> findHiddenTeamIdsByWorkspace(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceTeamSettings wts WHERE wts.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
