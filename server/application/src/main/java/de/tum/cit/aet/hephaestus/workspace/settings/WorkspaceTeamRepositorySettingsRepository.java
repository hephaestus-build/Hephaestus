package de.tum.cit.aet.hephaestus.workspace.settings;

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

/**
 * Repository for {@link WorkspaceTeamRepositorySettings} entities.
 *
 * <p>Manages workspace-scoped team repository settings, providing queries for:
 * <ul>
 *   <li>Fetching settings by workspace, team, and/or repository</li>
 *   <li>Finding repositories hidden from contributions within a workspace</li>
 * </ul>
 */
@Repository
@WorkspaceAgnostic("Queried by explicit workspace ID - settings queries always workspace-scoped")
public interface WorkspaceTeamRepositorySettingsRepository
        extends JpaRepository<WorkspaceTeamRepositorySettings, WorkspaceTeamRepositorySettings.Id> {
    /**
     * Finds all team repository settings for a given workspace.
     *
     * @param workspaceId the workspace ID
     * @return list of team repository settings for the workspace
     */
    List<WorkspaceTeamRepositorySettings> findByWorkspaceId(Long workspaceId);

    /**
     * Finds all repository settings for a specific workspace and team combination.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @return list of repository settings for the team in the workspace
     */
    List<WorkspaceTeamRepositorySettings> findByWorkspaceIdAndTeamId(Long workspaceId, Long teamId);

    /**
     * Finds settings for a specific workspace, team, and repository combination.
     *
     * @param workspaceId the workspace ID
     * @param teamId the team ID
     * @param repositoryId the repository ID
     * @return the settings if they exist
     */
    Optional<WorkspaceTeamRepositorySettings> findByWorkspaceIdAndTeamIdAndRepositoryId(
            Long workspaceId, Long teamId, Long repositoryId);

    /**
     * Finds all team repository settings where the repository is hidden from contributions
     * for a given workspace.
     *
     * @param workspaceId the workspace ID
     * @return list of settings for hidden repositories
     */
    @Query("""
        SELECT wtrs FROM WorkspaceTeamRepositorySettings wtrs
        WHERE wtrs.workspace.id = :workspaceId AND wtrs.hiddenFromContributions = true
        """)
    List<WorkspaceTeamRepositorySettings> findHiddenRepositoriesByWorkspace(@Param("workspaceId") Long workspaceId);

    /**
     * Finds the IDs of all repositories hidden from contributions for a given workspace.
     *
     * <p>This is a more efficient query when only repository IDs are needed,
     * avoiding the overhead of loading full entity graphs.
     *
     * @param workspaceId the workspace ID
     * @return set of repository IDs that are hidden from contributions
     */
    @Query("""
        SELECT wtrs.repository.id FROM WorkspaceTeamRepositorySettings wtrs
        WHERE wtrs.workspace.id = :workspaceId AND wtrs.hiddenFromContributions = true
        """)
    Set<Long> findHiddenRepositoryIdsByWorkspace(@Param("workspaceId") Long workspaceId);

    /**
     * Finds the IDs of repositories hidden from contributions for specific teams in a workspace.
     *
     * <p>This query is useful when filtering contributions for a subset of teams,
     * such as when calculating team-specific statistics.
     *
     * @param workspaceId the workspace ID
     * @param teamIds the set of team IDs to check
     * @return set of repository IDs that are hidden from contributions for the specified teams
     */
    @Query("""
        SELECT wtrs.repository.id FROM WorkspaceTeamRepositorySettings wtrs
        WHERE wtrs.workspace.id = :workspaceId
          AND wtrs.team.id IN :teamIds
          AND wtrs.hiddenFromContributions = true
        """)
    Set<Long> findHiddenRepositoryIdsByWorkspaceAndTeams(
            @Param("workspaceId") Long workspaceId, @Param("teamIds") Set<Long> teamIds);

    /**
     * Deletes all team repository settings for a workspace.
     * Used during workspace purge to clean up settings data.
     *
     * @param workspaceId the workspace ID
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorkspaceTeamRepositorySettings wtrs WHERE wtrs.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}
