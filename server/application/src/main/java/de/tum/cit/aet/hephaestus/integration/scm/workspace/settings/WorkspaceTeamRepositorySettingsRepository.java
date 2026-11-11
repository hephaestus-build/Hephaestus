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
public interface WorkspaceTeamRepositorySettingsRepository
        extends JpaRepository<WorkspaceTeamRepositorySettings, WorkspaceTeamRepositorySettings.Id> {
    List<WorkspaceTeamRepositorySettings> findByWorkspaceId(Long workspaceId);

    List<WorkspaceTeamRepositorySettings> findByWorkspaceIdAndTeamId(Long workspaceId, Long teamId);

    Optional<WorkspaceTeamRepositorySettings> findByWorkspaceIdAndTeamIdAndRepositoryId(
            Long workspaceId, Long teamId, Long repositoryId);

    @Query("""
        SELECT wtrs.repository.id FROM WorkspaceTeamRepositorySettings wtrs
        WHERE wtrs.workspace.id = :workspaceId AND wtrs.hiddenFromContributions = true
        """)
    Set<Long> findHiddenRepositoryIdsByWorkspace(@Param("workspaceId") Long workspaceId);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceTeamRepositorySettings wtrs WHERE wtrs.workspace.id =" + " :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
